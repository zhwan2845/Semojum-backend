package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;

/**
 * 폴링 API(`GET /api/jobs/{id}/status`)의 종료 판정 — DB와 같은 답을 내야 한다.
 *
 * <p>판정 규칙의 정본은 {@code ResultService.evaluateJobTermination}이다:
 * 전 쪽이 terminal일 때 성공(COMPLETED·NEEDS_REVIEW)이 0건이면 FAILED, 1건 이상이면 COMPLETED.
 * 예전엔 BLOCKED를 성공과 함께 세어 취소로 전 쪽이 BLOCKED된 작업을 COMPLETED라고 답했고,
 * 같은 작업을 DB로 읽는 운영자 화면은 FAILED라 서로 어긋났다(실측).
 */
class JobStatusOverallTest {

    JobStatusService jobStatusService;
    HashOperations<String, Object, Object> hashOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);
        hashOps = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);

        jobStatusService = Mockito.mock(JobStatusService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(jobStatusService, "redisTemplate", redisTemplate);
        dispatcher = Mockito.mock(JobDispatcher.class);
        ReflectionTestUtils.setField(jobStatusService, "jobDispatcher", dispatcher);
        jobRepository = Mockito.mock(JobRepository.class);
        ReflectionTestUtils.setField(jobStatusService, "jobRepository", jobRepository);
        pageRepository = Mockito.mock(PageRepository.class);
        ReflectionTestUtils.setField(jobStatusService, "pageRepository", pageRepository);
        when(jobRepository.findByIdAndUserId(eq("job-1"), eq(UUID.fromString(OWNER))))
                .thenReturn(Optional.of(Job.builder()
                        .id("job-1").mode("c").totalPages(3).originalFileName("a.pdf").build()));
    }

    static final String OWNER = UUID.randomUUID().toString();
    JobDispatcher dispatcher;
    JobRepository jobRepository;
    PageRepository pageRepository;

    /** 남의 작업 — 상태도 주지 않고 FG 리스도 올리지 않는다 */
    @Test
    void 남의_작업_상태는_403이고_우선순위도_올리지_않는다() {
        String stranger = UUID.randomUUID().toString();

        CustomException e = Assertions.assertThrows(
                CustomException.class,
                () -> jobStatusService.getJobStatus(stranger, "job-1"));
        Assertions.assertEquals(
                ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
        Mockito.verify(dispatcher, Mockito.never()).touchForeground(anyString());
        Mockito.verify(hashOps, Mockito.never()).entries(anyString());
    }

    /** Redis Hash 흉내 — total_pages + page:N 상태들 */
    private JobResponseDto.Status statusOf(String... pageStates) {
        Map<Object, Object> data = new LinkedHashMap<>();
        data.put("total_pages", String.valueOf(pageStates.length));
        for (int i = 0; i < pageStates.length; i++) data.put("page:" + (i + 1), pageStates[i]);
        when(hashOps.entries(anyString())).thenReturn(data);
        return jobStatusService.getJobStatus(OWNER, "job-1");
    }

    /**
     * 회귀 방지 본체 — 취소로 전 쪽이 BLOCKED된 작업.
     * DB(ResultService)는 FAILED로 쓰므로 폴링도 FAILED여야 한다.
     */
    @Test
    void 전_쪽이_BLOCKED면_FAILED다() {
        JobResponseDto.Status s = statusOf("BLOCKED", "BLOCKED", "BLOCKED");

        assertEquals("FAILED", s.overallStatus(), "성공 0건이면 FAILED (예전엔 COMPLETED였다)");
        assertEquals(3, s.completedPages(), "끝난 쪽 수는 BLOCKED를 포함해야 진행률이 100%에 닿는다");
    }

    /** 한 쪽이라도 결과가 나왔으면 부분 성공 = COMPLETED (205쪽 실측 사례: BLOCKED 11쪽 섞임) */
    @Test
    void 일부만_BLOCKED면_COMPLETED다() {
        assertEquals("COMPLETED", statusOf("NEEDS_REVIEW", "BLOCKED", "COMPLETED").overallStatus());
        assertEquals("COMPLETED", statusOf("BLOCKED", "BLOCKED", "COMPLETED").overallStatus(), "성공 1건이면 충분");
    }

    /** NEEDS_REVIEW는 결과가 나온 쪽이다 — 검토가 필요할 뿐 실패가 아니다 */
    @Test
    void NEEDS_REVIEW만_있어도_COMPLETED다() {
        assertEquals("COMPLETED", statusOf("NEEDS_REVIEW", "NEEDS_REVIEW").overallStatus());
    }

    /**
     * 진행률 회귀 방지 — completedPages에서 BLOCKED를 빼면 실패가 섞인 작업의 분자가
     * total에 영원히 못 닿아 화면이 "진행 중"에 멈춘다.
     */
    @Test
    void completedPages는_BLOCKED를_포함한다() {
        JobResponseDto.Status s = statusOf("COMPLETED", "BLOCKED", "NEEDS_REVIEW", "BLOCKED");

        assertEquals(4, s.completedPages());
        assertEquals(s.totalPages(), s.completedPages(), "끝났으면 분자 == 분모");
        assertEquals(0, s.pendingPages());
        assertEquals(0, s.runningPages());
    }

    /** 진행 중·미착수 판정은 그대로 */
    @Test
    void 진행_중과_미착수는_종전과_같다() {
        assertEquals("IN_PROGRESS", statusOf("COMPLETED", "RUNNING", "PENDING").overallStatus());
        assertEquals("IN_PROGRESS", statusOf("BLOCKED", "PENDING").overallStatus(), "실패 쪽이 생겨도 남았으면 진행 중");
        assertEquals("PENDING", statusOf("PENDING", "PENDING").overallStatus());
    }

    /** 크레딧 부족으로 막힌 쪽이 있으면 앱이 「크레딧 부족」을 알리도록 표시한다 */
    @Test
    void 크레딧_부족으로_막힌_쪽이_있으면_표시한다() {
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("total_pages", "2");
        hash.put("page:1", "COMPLETED");
        hash.put("page:2", "BLOCKED");
        when(hashOps.entries(anyString())).thenReturn(hash);
        when(pageRepository.existsByJob_IdAndBlockedReason("job-1",
                Page.BLOCKED_CREDIT_EXHAUSTED)).thenReturn(true);

        JobResponseDto.Status s = jobStatusService.getJobStatus(OWNER, "job-1");
        Assertions.assertTrue(s.creditExhausted());
        assertEquals("COMPLETED", s.overallStatus());
    }

    /**
     * 끝나고 1시간이 지나 상태 Hash 가 사라진 작업 — DB 로 답한다.
     * 종전엔 JOB4001 이라 다음 날 문서를 열면 앱이 「크레딧 소진」 알림 띠를 띄울 근거가 없었다.
     */
    @Test
    void Hash가_사라진_끝난_작업은_DB로_답한다() {
        Job job = Job.builder()
                .id("job-1").mode("c").totalPages(3).originalFileName("a.pdf").build();
        job.updateStatus("COMPLETED");
        when(jobRepository.findByIdAndUserId(eq("job-1"), eq(UUID.fromString(OWNER))))
                .thenReturn(Optional.of(job));
        when(hashOps.entries(anyString())).thenReturn(new LinkedHashMap<>());
        List<Page> dbPages = new ArrayList<>();
        String[] states = {"COMPLETED", "NEEDS_REVIEW", "BLOCKED"};
        for (int i = 0; i < 3; i++) {
            var page = Page.builder().job(job).pageNo(i + 1).pdfPath("p").build();
            if (i == 2) page.block(Page.BLOCKED_CREDIT_EXHAUSTED);
            else page.updateStatus(states[i]);
            dbPages.add(page);
        }
        when(pageRepository.findByJob(job)).thenReturn(dbPages);
        when(pageRepository.existsByJob_IdAndBlockedReason("job-1",
                Page.BLOCKED_CREDIT_EXHAUSTED)).thenReturn(true);

        JobResponseDto.Status s = jobStatusService.getJobStatus(OWNER, "job-1");

        assertEquals("COMPLETED", s.overallStatus());
        assertEquals(3, s.totalPages());
        assertEquals(3, s.completedPages());
        assertEquals("BLOCKED", s.pages().get("page:3"));
        Assertions.assertTrue(s.creditExhausted(), "띠 근거 — 크레딧 소진 쪽이 있다");
        Mockito.verify(dispatcher, Mockito.never()).touchForeground(anyString());
    }

    /** 텍스트 작업은 쪽 상태가 SAVED 라 쪽 수로 판정할 수 없다 — DB 작업 상태를 그대로 준다 */
    @Test
    void Hash가_없는_텍스트_작업은_DB_작업_상태를_준다() {
        Job job = Job.builder()
                .id("job-1").mode("b").totalPages(2).originalFileName("a.txt").build();
        job.updateStatus("COMPLETED");
        when(jobRepository.findByIdAndUserId(eq("job-1"), eq(UUID.fromString(OWNER))))
                .thenReturn(Optional.of(job));
        when(hashOps.entries(anyString())).thenReturn(new LinkedHashMap<>());
        var p1 = Page.builder().job(job).pageNo(1).pdfPath("").build();
        p1.updateStatus("SAVED");
        when(pageRepository.findByJob(job)).thenReturn(List.of(p1));

        assertEquals("COMPLETED", jobStatusService.getJobStatus(OWNER, "job-1").overallStatus());
    }
}
