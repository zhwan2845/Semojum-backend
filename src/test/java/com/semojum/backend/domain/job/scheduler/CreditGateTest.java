package com.semojum.backend.domain.job.scheduler;

import com.semojum.backend.domain.billing.service.CreditBalanceService;
import com.semojum.backend.domain.job.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 잔여 크레딧 관문 — 10 미만이면 기관 전체에서 한 쪽씩, 1 이상이면 보내고, 0 이하면 막는다.
 */
class CreditGateTest {

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();
    private final UUID userA1 = UUID.randomUUID();
    private final UUID userA2 = UUID.randomUUID();
    private CreditBalanceService balance;

    private static List<Object[]> owner(UUID user, UUID org) {
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{user, org});
        return rows;
    }
    private CreditGate gate;

    @BeforeEach
    void setUp() {
        balance = Mockito.mock(CreditBalanceService.class);
        // 계정 한도 기본값 = 제한 없음(null). Mockito는 Long을 0으로 돌려줘 「한도 소진」이 된다
        Mockito.lenient().when(balance.accountRemaining(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        JobRepository jobs = Mockito.mock(JobRepository.class);
        when(jobs.findOwnerById("a1")).thenReturn(owner(userA1, orgA));   // A기관 · 사람 1
        when(jobs.findOwnerById("a1b")).thenReturn(owner(userA1, orgA));  // A기관 · 사람 1의 다른 작업
        when(jobs.findOwnerById("a2")).thenReturn(owner(userA2, orgA));   // A기관 · 사람 2
        when(jobs.findOwnerById("b1")).thenReturn(owner(UUID.randomUUID(), orgB));
        when(jobs.findOwnerById("free")).thenReturn(owner(UUID.randomUUID(), null)); // 무소속(운영자)
        gate = new CreditGate(balance, jobs);
    }

    @Test
    void 잔여_10_이상이면_동시에_여러_쪽을_보낸다() {
        when(balance.remaining(orgA)).thenReturn(10L);
        gate.acquire("t1", "a1");
        assertTrue(gate.mayDispatch("a1"));
        assertTrue(gate.mayDispatch("a2"));
    }

    /** 결과를 받아야 차감되므로 동시에 보낸 쪽은 모두 같은 잔액을 보고 통과한다 — 그래서 한 쪽씩 */
    @Test
    void 잔여_10_미만이면_기관_쪽이_AI에_있는_동안_다음_쪽을_안_보낸다() {
        when(balance.remaining(orgA)).thenReturn(9L);
        assertTrue(gate.mayDispatch("a1"));
        gate.acquire("t1", "a1");

        assertFalse(gate.mayDispatch("a1"));
        assertFalse(gate.mayDispatch("a2"), "같은 기관의 다른 사람도 기다린다 — 기관 전체 기준");

        gate.release("t1");
        assertTrue(gate.mayDispatch("a2"));
    }

    @Test
    void 다른_기관은_막히지_않는다() {
        when(balance.remaining(orgA)).thenReturn(3L);
        when(balance.remaining(orgB)).thenReturn(3L);
        gate.acquire("t1", "a1");
        assertTrue(gate.mayDispatch("b1"));
    }

    /** 2 남았는데 그 쪽이 5를 써도 보낸다 — 거기까지는 처리해 준다 */
    @Test
    void 잔여_1_이상이면_보내고_0_이하면_막는다() {
        when(balance.remaining(orgA)).thenReturn(1L);
        assertFalse(gate.exhausted("a1"));

        when(balance.remaining(orgA)).thenReturn(0L);
        assertTrue(gate.exhausted("a1"));
        when(balance.remaining(orgA)).thenReturn(-3L);
        assertTrue(gate.exhausted("a1"));
    }

    /** 0 이하인 작업의 쪽은 꺼내 준다 — 워커가 AI에 보내지 않고 바로 막아야 작업이 끝난다 */
    @Test
    void 잔여_0_이하면_꺼내_주되_워커가_막는다() {
        when(balance.remaining(orgA)).thenReturn(-3L);
        gate.acquire("t1", "a1");
        assertTrue(gate.mayDispatch("a2"));
    }

    /** 무소속 계정은 기관 잔여를 보지 않는다(계정 한도는 걸려 있으면 본다) */
    @Test
    void 무소속_계정은_기관_잔여로_제한하지_않는다() {
        assertTrue(gate.mayDispatch("free"));
        assertFalse(gate.exhausted("free"));
        Mockito.verify(balance, Mockito.never()).remaining(org.mockito.ArgumentMatchers.any());
    }

    // ── 계정 한도 ──────────────────────────────────────────────────

    /** 기관 잔여가 넉넉해도 이번 달 계정 한도를 다 썼으면 막는다 */
    @Test
    void 계정_한도를_다_쓰면_기관_잔여가_있어도_막는다() {
        when(balance.remaining(orgA)).thenReturn(5000L);
        when(balance.accountRemaining(userA1)).thenReturn(0L);
        assertTrue(gate.exhausted("a1"));
        assertFalse(gate.exhausted("a2"), "같은 기관의 다른 계정은 한도와 무관하다");
    }

    /** 계정 잔여 10 미만이면 그 계정에서 한 쪽씩 — 다른 계정은 기다리지 않는다 */
    @Test
    void 계정_잔여_10_미만이면_그_계정에서만_한_쪽씩() {
        when(balance.remaining(orgA)).thenReturn(5000L);
        when(balance.accountRemaining(userA1)).thenReturn(4L);
        gate.acquire("t1", "a1");
        assertFalse(gate.mayDispatch("a1b"), "같은 계정의 다른 작업도 기다린다");
        assertTrue(gate.mayDispatch("a2"));
        gate.release("t1");
        assertTrue(gate.mayDispatch("a1b"));
    }

    @Test
    void 한도가_없으면_계정_잔여를_보지_않는다() {
        when(balance.remaining(orgA)).thenReturn(5000L);
        when(balance.accountRemaining(userA1)).thenReturn(null);
        gate.acquire("t1", "a1");
        assertTrue(gate.mayDispatch("a1b"));
        assertFalse(gate.exhausted("a1"));
    }
}
