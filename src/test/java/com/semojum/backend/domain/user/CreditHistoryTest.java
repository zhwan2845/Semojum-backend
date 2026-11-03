package com.semojum.backend.domain.user;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.support.entity.Order;
import com.semojum.backend.domain.support.repository.OrderRepository;
import com.semojum.backend.domain.user.dto.UsageDto;
import com.semojum.backend.domain.user.service.CreditHistoryService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 마이페이지 과금 이력 상세 — 내 사용(작업별) + 기관 충전(크레딧 추가 주문) */
class CreditHistoryTest {

    UserRepository userRepository;
    CreditTransactionRepository creditRepository;
    JobRepository jobRepository;
    OrderRepository orderRepository;
    CreditHistoryService service;
    Organization org;
    User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        creditRepository = mock(CreditTransactionRepository.class);
        jobRepository = mock(JobRepository.class);
        orderRepository = mock(OrderRepository.class);
        service = new CreditHistoryService(userRepository, creditRepository, jobRepository, orderRepository);

        org = Organization.builder().name("기관").code("org").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(org, "creditAllocated", 10_000L);
        user = User.builder().loginId("testorg01").organization(org).password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(creditRepository.sumContractByOrganization(org.getId())).thenReturn(4_600L);

        Job job = Job.builder().id("job_1").mode("c").totalPages(14).originalFileName("수능특강_생명Ⅱ.pdf").build();
        when(jobRepository.findAllById(List.of("job_1"))).thenReturn(List.of(job));
        when(creditRepository.sumPerJobByUserBetween(eq(user.getId()), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"job_1", Instant.parse("2026-08-13T01:24:00Z"), 14L, 11L}));
        Order charge = Order.builder().organizationId(org.getId()).orderDate(LocalDate.of(2026, 8, 1))
                .description("크레딧 추가 (주문 20260801-02)").amountKrw(780_000).creditAmount(3000L).build();
        Order noCredit = Order.builder().organizationId(org.getId()).orderDate(LocalDate.of(2026, 8, 2))
                .description("연간 유지보수").amountKrw(100_000).build();
        when(orderRepository.findByOrganizationIdOrderByOrderDateDesc(org.getId())).thenReturn(List.of(charge, noCredit));
        // 기관 계약분 사용 — 내 작업(08-13 11)과 다른 계정 사용(08-05 7)
        when(creditRepository.findContractUsageSince(eq(org.getId()), any())).thenReturn(List.<Object[]>of(
                new Object[]{Instant.parse("2026-08-05T03:00:00Z"), 7L},
                new Object[]{Instant.parse("2026-08-13T01:24:00Z"), 11L}));
    }

    private UsageDto.CreditHistory history(String type) {
        return service.getHistory(user.getId().toString(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31), type);
    }

    @Test
    void 사용은_작업별_음수_충전은_주문의_양수로_최신순() {
        UsageDto.CreditHistory h = history(null);

        assertEquals(2, h.items().size());
        UsageDto.CreditHistoryItem use = h.items().get(0);
        assertEquals(LocalDate.of(2026, 8, 13), use.date());
        assertEquals("USE", use.type());
        assertEquals("수능특강_생명Ⅱ.pdf", use.title());
        assertEquals(14, use.pages());
        assertEquals(-11, use.credits());

        UsageDto.CreditHistoryItem charge = h.items().get(1);
        assertEquals("CHARGE", charge.type());
        assertEquals(3000, charge.credits());
        assertNull(charge.pages());
        assertEquals(5_400L, h.orgRemaining());
    }

    @Test
    void 구분으로_거른다() {
        assertTrue(history("USE").items().stream().allMatch(i -> i.type().equals("USE")));
        assertTrue(history("CHARGE").items().stream().allMatch(i -> i.type().equals("CHARGE")));
        verify(creditRepository, times(1)).sumPerJobByUserBetween(any(), any(), any());   // CHARGE는 사용을 읽지 않는다
    }

    @Test
    void 기간_밖의_충전은_빠진다() {
        UsageDto.CreditHistory h = service.getHistory(user.getId().toString(),
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 31), "CHARGE");
        assertTrue(h.items().isEmpty());
    }

    /** 무소속 계정 — 기관 충전 · 잔여가 없다 */
    @Test
    void 무소속은_충전과_잔여가_없다() {
        ReflectionTestUtils.setField(user, "organization", null);
        UsageDto.CreditHistory h = history(null);
        assertNull(h.orgRemaining());
        assertTrue(h.items().stream().noneMatch(i -> i.type().equals("CHARGE")));
    }

    @Test
    void 모르는_구분이나_뒤집힌_기간은_400이다() {
        for (Runnable bad : List.<Runnable>of(
                () -> history("REFUND"),
                () -> service.getHistory(user.getId().toString(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1), null))) {
            CustomException e = assertThrows(CustomException.class, bad::run);
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
    }

    /** 피그마 「잔여 크레딧」 열 — 지금 잔여에서 거꾸로: 지금 + 그 뒤 기관 사용 − 그 뒤 충전 */
    @Test
    void 행마다_그_뒤의_기관_잔여를_싣는다() {
        UsageDto.CreditHistory h = history(null);
        assertEquals(5_400L, h.items().get(0).balanceAfter(), "가장 최근 행 뒤 = 지금 잔여");
        // 08-01 충전 뒤: 지금 5,400 + 그 뒤 사용(7 + 11) − 그 뒤 충전(없음, 자기 자신은 뺀다)
        assertEquals(5_418L, h.items().get(1).balanceAfter());
    }

    @Test
    void 구분으로_걸러도_잔여는_전체_기준이다() {
        assertEquals(5_418L, history("CHARGE").items().get(0).balanceAfter());
    }
}
