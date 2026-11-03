package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.support.entity.Order;
import com.semojum.backend.domain.support.repository.OrderRepository;
import com.semojum.backend.domain.user.dto.UsageDto;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 마이페이지 [과금 이력] 상세 내역 — 내 사용(작업별 차감)과 우리 기관의 충전(크레딧 추가 주문)을 한 표로.
 *
 * <p>충전은 기관 단위라 같은 기관 계정 모두에게 보인다. 사용은 <b>내 것만</b> 보인다(열람 범위).
 */
@Service
@RequiredArgsConstructor
public class CreditHistoryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final String USE = "USE";
    public static final String CHARGE = "CHARGE";
    static final Set<String> TYPES = Set.of("ALL", USE, CHARGE);

    private final UserRepository userRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final JobRepository jobRepository;
    private final OrderRepository orderRepository;

    /**
     * @param from · to 기간(KST 날짜, 끝 날 포함) — 기본은 올해 1월 1일 ~ 오늘(화면 「2026년」)
     * @param type      ALL(기본) · USE · CHARGE — 화면 「전체 / 사용 / 충전」
     */
    @Transactional(readOnly = true)
    public UsageDto.CreditHistory getHistory(String userId, LocalDate from, LocalDate to, String type) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        String t = type == null || type.isBlank() ? "ALL" : type;
        if (!TYPES.contains(t)) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        LocalDate toDate = to != null ? to : LocalDate.now(KST);
        LocalDate fromDate = from != null ? from : toDate.withDayOfYear(1);
        if (fromDate.isAfter(toDate)) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);

        Organization org = user.getOrganization();
        List<Order> charges = org == null ? List.of() : orderRepository.findByOrganizationIdOrderByOrderDateDesc(org.getId())
                .stream().filter(o -> o.getCreditAmount() != null && o.getCreditAmount() > 0).toList();

        // 행 잔여는 행마다 따로 센다 — 거른 구분의 행만 만들어도 값이 같다
        List<Row> rows = new ArrayList<>(CHARGE.equals(t) ? List.of() : usage(user, fromDate, toDate));
        for (Order o : USE.equals(t) ? List.<Order>of() : charges) {
            if (o.getOrderDate().isBefore(fromDate) || o.getOrderDate().isAfter(toDate)) continue;
            rows.add(new Row(o.getOrderDate().atStartOfDay(KST).toInstant(), o.getOrderDate(), CHARGE,
                    o.getDescription(), null, o.getCreditAmount(), null, o.getId()));
        }
        rows.sort(Comparator.comparing(Row::at).reversed());

        Long orgRemaining = null;
        List<Long> balances = new ArrayList<>();
        if (org != null) {
            // 사용량 화면 카드와 같은 계산 — 계약분 차감만 잔여를 깎는다
            orgRemaining = org.getCreditAllocated() - creditTransactionRepository.sumContractByOrganization(org.getId());
            balances = balancesAfter(org, orgRemaining, rows, charges, fromDate);
        }

        List<UsageDto.CreditHistoryItem> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            items.add(new UsageDto.CreditHistoryItem(r.date(), r.type(), r.title(), r.pages(), r.credits(),
                    r.jobId(), r.orderId(), org == null ? null : balances.get(i)));
        }
        return new UsageDto.CreditHistory(fromDate, toDate, t, orgRemaining, items);
    }

    /** 표의 한 행 — 잔여를 세려고 시각까지 들고 다닌다 */
    private record Row(Instant at, LocalDate date, String type, String title, Integer pages, long credits,
                       String jobId, UUID orderId) {}

    /**
     * 행마다 그 뒤의 기관 잔여 — 지금 잔여에서 거꾸로 센다: 지금 잔여 + 그 행 뒤에 기관이 쓴 양 − 그 행 뒤의 충전.
     *
     * <p>충전 기록은 주문(크레딧 양이 있는 주문, 주문일 0시)이다 — 배정량 변경 이력이 따로 없어서다. 운영자가 주문 없이
     * 배정량을 바꿨으면 그 앞 행의 잔여가 그만큼 어긋난다.
     */
    private List<Long> balancesAfter(Organization org, long remainingNow, List<Row> rows, List<Order> charges,
                                     LocalDate from) {
        List<Object[]> usage = creditTransactionRepository.findContractUsageSince(
                org.getId(), from.atStartOfDay(KST).toInstant());
        List<Long> balances = new ArrayList<>(rows.size());
        for (Row r : rows) {
            long usedAfter = 0;
            for (Object[] u : usage) if (((Instant) u[0]).isAfter(r.at())) usedAfter += ((Number) u[1]).longValue();
            long chargedAfter = 0;
            for (Order o : charges) {
                if (o.getOrderDate().atStartOfDay(KST).toInstant().isAfter(r.at())) chargedAfter += o.getCreditAmount();
            }
            balances.add(remainingNow + usedAfter - chargedAfter);
        }
        return balances;
    }

    /** 내 차감을 작업 하나에 한 행으로 — 날짜는 그 작업의 마지막 차감 시각 */
    private List<Row> usage(User user, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(KST).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(KST).toInstant();
        List<Object[]> rows = creditTransactionRepository.sumPerJobByUserBetween(user.getId(), start, end);
        if (rows.isEmpty()) return List.of();

        Map<String, Job> jobs = jobRepository.findAllById(rows.stream().map(r -> (String) r[0]).toList()).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
        return rows.stream().map(r -> {
            String jobId = (String) r[0];
            Job job = jobs.get(jobId);
            Instant at = (Instant) r[1];
            return new Row(at, LocalDate.ofInstant(at, KST), USE, job == null ? null : job.getOriginalFileName(),
                    ((Number) r[2]).intValue(), -((Number) r[3]).longValue(), jobId, null);
        }).toList();
    }
}
