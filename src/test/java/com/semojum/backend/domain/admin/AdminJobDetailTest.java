package com.semojum.backend.domain.admin;

import com.semojum.backend.domain.admin.dto.AdminMonitorDto;
import com.semojum.backend.domain.admin.service.AdminMonitorService;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.billing.entity.CreditTransaction;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.service.JobProgressReader;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.QualityCriticalError;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.domain.result.repository.QualityCriticalErrorRepository;
import com.semojum.backend.global.client.GeoIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 운영자 작업 상세 — 쪽별 최신 결과 · 실패 사유 · 차감 크레딧 · 합계 (저장소 mock) */
class AdminJobDetailTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final PageRepository pageRepository = mock(PageRepository.class);
    private final PageResultRepository resultRepository = mock(PageResultRepository.class);
    private final QualityCriticalErrorRepository errorRepository = mock(QualityCriticalErrorRepository.class);
    private final CreditTransactionRepository creditRepository = mock(CreditTransactionRepository.class);
    private final GeoIpResolver geoIpResolver = mock(GeoIpResolver.class);
    private final AdminMonitorService service = new AdminMonitorService(jobRepository, pageRepository,
            resultRepository, errorRepository, creditRepository, mock(JobProgressReader.class), geoIpResolver);

    private PageResult result(Job job, int pageNo, String layout, String costKrw, LocalDateTime createdAt) {
        PageResult r = PageResult.builder().job(job).pageNumber(pageNo).status("COMPLETED")
                .layoutType(layout).costKrw(new BigDecimal(costKrw)).build();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        return r;
    }

    private Page page(Job job, int no, String status) {
        Page p = Page.builder().job(job).pageNo(no).pdfPath("p" + no).build();
        p.updateStatus(status);
        return p;
    }

    @Test
    void 쪽마다_최신_결과로_사유_크레딧_합계를_만든다() {
        User user = User.builder().loginId("user01").password("x").build();
        Job job = Job.builder().id("job-1").user(user).mode("c").totalPages(3).originalFileName("a.pdf").build();
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(geoIpResolver.resolve(any())).thenReturn("서울");
        // 2쪽은 재시도로 결과가 두 벌 — 나중 것만 써야 한다
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 1, 9, 0);
        PageResult p1 = result(job, 1, "PAGE_LAYOUT_TEXT", "10", t0);
        PageResult p2old = result(job, 2, "PAGE_LAYOUT_TABLE", "99", t0);
        PageResult p2new = result(job, 2, "PAGE_LAYOUT_TEXT", "20", t0.plusMinutes(5));
        when(pageRepository.findByJobId("job-1")).thenReturn(List.of(
                page(job, 3, "BLOCKED"), page(job, 1, "COMPLETED"), page(job, 2, "NEEDS_REVIEW")));
        when(resultRepository.findByJobIdOrderByPageNumber("job-1")).thenReturn(List.of(p1, p2new, p2old));
        when(errorRepository.findByPageResultIdIn(any())).thenReturn(List.of(
                QualityCriticalError.builder().pageResult(p2new).message("표 누락").build()));
        when(creditRepository.findByJobIdOrderByPageNo("job-1")).thenReturn(List.of(
                CreditTransaction.builder().jobId("job-1").pageNo(1).amount(1).build(),
                CreditTransaction.builder().jobId("job-1").pageNo(2).amount(3).build()));

        AdminMonitorDto.JobDetail d = service.getJobDetail("job-1");

        assertEquals("user01", d.request().loginId());
        assertEquals(List.of(1, 2, 3), d.pages().stream().map(AdminMonitorDto.PageRow::pageNo).toList());
        AdminMonitorDto.PageRow row2 = d.pages().get(1);
        assertEquals("PAGE_LAYOUT_TEXT", row2.layoutType());
        assertEquals(new BigDecimal("20"), row2.costKrw());
        assertEquals(3, row2.credit());
        assertEquals(List.of("표 누락"), row2.reasons());
        AdminMonitorDto.PageRow row3 = d.pages().get(2);
        assertNull(row3.layoutType());
        assertNull(row3.credit());
        assertEquals(List.of(), row3.reasons());

        AdminMonitorDto.ProcessingInfo p = d.processing();
        assertEquals(2, p.successPages());
        assertEquals(1, p.failedPages());
        assertEquals(new BigDecimal("30"), p.costKrw());
        assertEquals(4, p.credits());
        assertEquals(Map.of("PAGE_LAYOUT_TEXT", 2), p.layoutCounts());
    }
}
