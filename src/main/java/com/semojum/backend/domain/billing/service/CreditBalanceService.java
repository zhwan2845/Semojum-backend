package com.semojum.backend.domain.billing.service;

import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.billing.repository.CouponRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기관의 잔여 크레딧 — 변환 스케줄러가 「몇 쪽을 동시에 보낼지 · 더 보낼지」를 정할 때 쓴다.
 *
 * <p>잔여 = 계약 배정량 − 계약에서 쓴 양 + 오늘 유효한 쿠폰의 남은 양. 쿠폰도 변환에 쓰이는 크레딧이라
 * 더한다(차감도 쿠폰부터 한다 — {@link CreditDeductionService}). 화면의 잔여 게이지는 계약분만 보여주므로
 * 이 값과 다를 수 있다.
 *
 * <p>스케줄러는 대기 중인 쪽이 있으면 100ms마다 묻는다. 매번 합산 쿼리를 돌리지 않도록 기관별로
 * 잠깐(2초) 기억하고, 차감이 <b>커밋된 뒤</b> 그 기관 값을 버린다 — 커밋 전에 버리면 다른 스레드가
 * 차감 전 합계를 다시 읽어 2초 동안 옛 값을 쓴다.
 *
 * <p>계정에 크레딧 한도가 있으면 {@link #accountRemaining}도 함께 본다 — 한도 − 이번 달(KST) 쓴 양.
 * 기관 잔여와 계정 잔여 중 먼저 바닥나는 쪽에서 멈춘다.
 */
@Service
@RequiredArgsConstructor
public class CreditBalanceService {

    static final long CACHE_MILLIS = 2_000;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OrganizationRepository organizationRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;

    private record Cached(long remaining, long at) {}

    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    /** 계정 잔여 — 한도가 없으면 Long.MAX_VALUE로 기억한다(매번 한도를 다시 읽지 않게) */
    private final Map<UUID, Cached> accountCache = new ConcurrentHashMap<>();

    public long remaining(UUID orgId) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(orgId);
        if (c != null && now - c.at() < CACHE_MILLIS) return c.remaining();

        long value = compute(orgId);
        cache.put(orgId, new Cached(value, now));
        return value;
    }

    private long compute(UUID orgId) {
        Long allocated = organizationRepository.findCreditAllocated(orgId);
        long contractLeft = (allocated != null ? allocated : 0)
                - creditTransactionRepository.sumContractByOrganization(orgId);
        LocalDate today = LocalDate.now();
        long couponLeft = couponRepository.sumActiveAmount(orgId, today)
                - creditTransactionRepository.sumUsedOnActiveCoupons(orgId, today);
        return contractLeft + Math.max(0, couponLeft);
    }

    /**
     * 계정 잔여 — 한도 − 이번 달(KST 달력 월) 쓴 양. 한도가 없으면(null) null = 제한 없음.
     * 음수일 수 있다(마지막 쪽은 한도를 넘겨도 끝까지 처리한다 — 기관 잔여와 같은 규칙).
     */
    public Long accountRemaining(UUID userId) {
        long now = System.currentTimeMillis();
        Cached c = accountCache.get(userId);
        if (c == null || now - c.at() >= CACHE_MILLIS) {
            c = new Cached(computeAccount(userId), now);
            accountCache.put(userId, c);
        }
        return c.remaining() == Long.MAX_VALUE ? null : c.remaining();
    }

    private long computeAccount(UUID userId) {
        Long limit = userRepository.findCreditLimitById(userId);
        if (limit == null) return Long.MAX_VALUE;
        YearMonth month = YearMonth.now(KST);
        Instant from = month.atDay(1).atStartOfDay(KST).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant();
        return limit - creditTransactionRepository.sumByUserBetween(userId, from, to);
    }

    /** 이 기관의 크레딧이 바뀌었다 — 트랜잭션 안이면 커밋 뒤에 버린다 */
    public void evict(UUID orgId) {
        if (orgId != null) afterCommit(() -> cache.remove(orgId));
    }

    /** 이 계정의 사용량이나 한도가 바뀌었다 — 트랜잭션 안이면 커밋 뒤에 버린다 */
    public void evictAccount(UUID userId) {
        if (userId != null) afterCommit(() -> accountCache.remove(userId));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
