package com.semojum.backend.domain.billing;

import com.semojum.backend.domain.billing.repository.CouponRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.billing.service.CreditBalanceService;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 잔여 크레딧 = 계약 배정 − 계약 사용 + 유효 쿠폰 남은 양 */
class CreditBalanceServiceTest {

    private final UUID org = UUID.randomUUID();
    private OrganizationRepository orgs;
    private CreditTransactionRepository txs;
    private CouponRepository coupons;
    private CreditBalanceService service;
    private com.semojum.backend.domain.auth.repository.UserRepository users;

    @BeforeEach
    void setUp() {
        orgs = mock(OrganizationRepository.class);
        txs = mock(CreditTransactionRepository.class);
        coupons = mock(CouponRepository.class);
        users = mock(com.semojum.backend.domain.auth.repository.UserRepository.class);
        service = new CreditBalanceService(orgs, txs, coupons, users);
        when(orgs.findCreditAllocated(org)).thenReturn(1000L);
        when(txs.sumContractByOrganization(org)).thenReturn(261L);
    }

    @Test
    void 계약_잔여에_유효_쿠폰_남은_양을_더한다() {
        when(coupons.sumActiveAmount(eq(org), any())).thenReturn(100L);
        when(txs.sumUsedOnActiveCoupons(eq(org), any())).thenReturn(30L);
        assertEquals(1000 - 261 + 70, service.remaining(org));
    }

    /** 배정 0인 기관이 계약에서 써 왔으면 마이너스다 — 변환이 막혀야 한다 */
    @Test
    void 계약은_마이너스가_될_수_있다() {
        when(orgs.findCreditAllocated(org)).thenReturn(0L);
        when(txs.sumContractByOrganization(org)).thenReturn(104L);
        assertEquals(-104, service.remaining(org));
    }

    @Test
    void 잠깐_기억하고_차감되면_버린다() {
        service.remaining(org);
        service.remaining(org);
        verify(txs, times(1)).sumContractByOrganization(org);

        service.evict(org);   // 트랜잭션 밖 — 바로 버린다
        when(txs.sumContractByOrganization(org)).thenReturn(266L);
        assertEquals(1000 - 266, service.remaining(org));
    }

    /** 계정 잔여 = 한 달 한도 − 이번 달 쓴 양. 한도가 없으면 null(제한 없음) */
    @Test
    void 계정_잔여는_한도에서_이번_달_사용을_뺀다() {
        UUID user = UUID.randomUUID();
        when(users.findCreditLimitById(user)).thenReturn(2000L);
        when(txs.sumByUserBetween(eq(user), any(), any())).thenReturn(1140L);
        assertEquals(860L, service.accountRemaining(user));

        UUID free = UUID.randomUUID();
        when(users.findCreditLimitById(free)).thenReturn(null);
        assertNull(service.accountRemaining(free));
    }

    /** 한도를 바꾸면 기억한 값을 버린다 — 다음 쪽부터 새 한도가 걸린다 */
    @Test
    void 계정_잔여도_버리면_새로_읽는다() {
        UUID user = UUID.randomUUID();
        when(users.findCreditLimitById(user)).thenReturn(100L);
        assertEquals(100L, service.accountRemaining(user));
        when(users.findCreditLimitById(user)).thenReturn(0L);
        assertEquals(100L, service.accountRemaining(user), "2초 동안은 기억한 값");
        service.evictAccount(user);
        assertEquals(0L, service.accountRemaining(user));
    }
}
