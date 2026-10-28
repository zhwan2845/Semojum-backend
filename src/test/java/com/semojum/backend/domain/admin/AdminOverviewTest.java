package com.semojum.backend.domain.admin;

import com.semojum.backend.domain.admin.dto.AdminStatsDto;
import com.semojum.backend.domain.admin.repository.AdminStatsRepository;
import com.semojum.backend.domain.admin.service.AdminStatsService;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.billing.repository.PricingConfigRepository;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 운영자 대표 통계 — 기간 탭의 범위 · 건수 분류 · 시계열 병합 (저장소 mock) */
class AdminOverviewTest {

    private AdminStatsRepository statsRepository;
    private AdminStatsService service;

    @BeforeEach
    void setUp() {
        statsRepository = mock(AdminStatsRepository.class);
        service = new AdminStatsService(statsRepository, mock(CreditTransactionRepository.class),
                mock(OrganizationRepository.class), mock(PricingConfigRepository.class));
        when(statsRepository.costSum(any(), any())).thenReturn(new Object[]{BigDecimal.ZERO, false});
    }

    @Test
    void 이번_주는_월요일부터_직전_주와_비교하고_일_단위로_묶는다() {
        LocalDateTime monday = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();

        AdminStatsDto.Overview o = service.getOverview("week");

        assertEquals("week", o.period());
        assertEquals(monday, o.from());
        verify(statsRepository).successPages(monday.minusWeeks(1), monday);
        verify(statsRepository).pagesSeries(eq(monday), any(), eq("day"));
    }

    @Test
    void 기간을_안_주면_오늘이고_시간_단위로_묶는다() {
        LocalDateTime today = LocalDate.now().atStartOfDay();

        AdminStatsDto.Overview o = service.getOverview(null);

        assertEquals("today", o.period());
        assertEquals(today, o.from());
        verify(statsRepository).successPages(today.minusDays(1), today);
        verify(statsRepository).jobsSeries(eq(today), any(), eq("hour"));
    }

    @Test
    void 이번_달은_1일부터_직전_달과_비교한다() {
        LocalDateTime first = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        service.getOverview("month");

        verify(statsRepository).successPages(first.minusMonths(1), first);
    }

    @Test
    void 모르는_기간은_조회하지_않고_400() {
        CustomException e = assertThrows(CustomException.class, () -> service.getOverview("year"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        verifyNoInteractions(statsRepository);
    }

    @Test
    void 취소는_실패로_세고_시계열은_쪽수와_건수를_한_칸에_합친다() {
        LocalDateTime h9 = LocalDate.now().atTime(9, 0), h10 = LocalDate.now().atTime(10, 0);
        when(statsRepository.jobStatusCounts(any(), any())).thenReturn(List.of(
                new Object[]{"COMPLETED", false, 5L}, new Object[]{"COMPLETED", true, 1L},
                new Object[]{"FAILED", false, 2L}, new Object[]{"IN_PROGRESS", false, 3L}));
        when(statsRepository.pagesSeries(any(), any(), any())).thenReturn(List.<Object[]>of(new Object[]{h9, 40L}));
        when(statsRepository.jobsSeries(any(), any(), any())).thenReturn(List.of(new Object[]{h9, 4L}, new Object[]{h10, 1L}));

        AdminStatsDto.Overview o = service.getOverview("today");

        assertEquals(new AdminStatsDto.JobCounts(11, 5, 3, 3), o.jobs());
        assertEquals(List.of(new AdminStatsDto.SeriesPoint(h9, 40, 4), new AdminStatsDto.SeriesPoint(h10, 0, 1)), o.series());
    }
}
