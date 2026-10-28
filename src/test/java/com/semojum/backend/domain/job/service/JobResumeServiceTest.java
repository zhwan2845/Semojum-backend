package com.semojum.backend.domain.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.billing.service.CreditBalanceService;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.CreditGate;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.semojum.backend.domain.result.service.PageResultCleaner;

/**
 * 이어서 처리 — 실제 Redis(Testcontainers) + 실제 스케줄러 큐 + 모의 리포지토리.
 * 핵심: 크레딧 소진으로 막힌 쪽만 다시 돈다 / 이미 처리한 쪽 · 오류로 막힌 쪽은 건드리지 않는다 /
 * 잔여가 없거나 사본이거나 진행 중이면 아무것도 바꾸지 않는다.
 */
class JobResumeServiceTest {

    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    static final String JOB = "job-resume-test";
    static final UUID USER_ID = UUID.randomUUID();
    static final UUID ORG_ID = UUID.randomUUID();

    JobRepository jobRepository;
    PageRepository pageRepository;
    CreditBalanceService creditBalanceService;
    PageResultCleaner pageResultCleaner;
    JobResumeService service;
    Job job;
    List<Page> pages;

    @BeforeAll
    static void startRedis() {
        redis.start();
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        jobRepository = Mockito.mock(JobRepository.class);
        pageRepository = Mockito.mock(PageRepository.class);
        creditBalanceService = Mockito.mock(CreditBalanceService.class);
        JobDispatcher dispatcher = new JobDispatcher(redisTemplate, jobRepository, new ObjectMapper(),
                Mockito.mock(CreditGate.class));
        pageResultCleaner = Mockito.mock(PageResultCleaner.class);
        service = new JobResumeService(jobRepository, pageRepository, dispatcher, creditBalanceService, redisTemplate,
                pageResultCleaner);
        when(jobRepository.findOrganizationIdById(JOB)).thenReturn(ORG_ID);
        when(creditBalanceService.remaining(ORG_ID)).thenReturn(50L);
        when(jobRepository.reopenJob(JOB)).thenReturn(1);
    }

    /** 쪽 상태를 지정해 끝난 작업을 만든다. "CREDIT" = 크레딧 소진 BLOCKED, "BLOCKED" = 오류 BLOCKED */
    private void givenFinishedJob(boolean adminCopy, String... states) {
        User user = User.builder().loginId("testorg01").build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        job = Job.builder().id(JOB).user(user).mode("c").totalPages(states.length)
                .originalFileName("a.pdf").adminCopy(adminCopy).build();
        job.updateStatus("COMPLETED");
        pages = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            Page page = Page.builder().job(job).pageNo(i + 1).pdfPath("s3/" + JOB + "/page-" + (i + 1) + ".pdf").build();
            if ("CREDIT".equals(states[i])) page.block(Page.BLOCKED_CREDIT_EXHAUSTED);
            else if ("CANCELED".equals(states[i])) page.block(Page.BLOCKED_CANCELED);
            else if ("BLOCKED".equals(states[i])) page.block(null);
            else page.updateStatus(states[i]);
            pages.add(page);
        }
        when(jobRepository.findByIdAndUserId(eq(JOB), any())).thenReturn(Optional.of(job));
        when(pageRepository.findByJob(job)).thenAnswer(inv -> new ArrayList<>(pages));
        when(pageRepository.existsByJob_IdAndBlockedReason(JOB, Page.BLOCKED_CREDIT_EXHAUSTED))
                .thenAnswer(inv -> pages.stream().anyMatch(p -> Page.BLOCKED_CREDIT_EXHAUSTED.equals(p.getBlockedReason())));
        when(pageRepository.findByJobAndBlockedReasonOrderByPageNoAsc(job, Page.BLOCKED_CREDIT_EXHAUSTED))
                .thenAnswer(inv -> pages.stream().filter(p -> Page.BLOCKED_CREDIT_EXHAUSTED.equals(p.getBlockedReason())).toList());
        when(pageRepository.existsByJobAndStatusAndBlockedReasonIsNull(job, "BLOCKED"))
                .thenAnswer(inv -> pages.stream().anyMatch(JobResumeServiceTest::failed));
        when(pageRepository.findByJobAndStatusAndBlockedReasonIsNullOrderByPageNoAsc(job, "BLOCKED"))
                .thenAnswer(inv -> pages.stream().filter(JobResumeServiceTest::failed).toList());
    }

    /** 사유 없이 막힌 쪽 — AI 오류 · 시간 초과 · 재시도 초과 */
    private static boolean failed(Page p) {
        return "BLOCKED".equals(p.getStatus()) && p.getBlockedReason() == null;
    }

    private String hashKey() { return "job:" + JOB + ":pages"; }

    private List<String> queue() {
        return redisTemplate.opsForList().range(JobDispatcher.jobQueueKey(JOB), 0, -1);
    }

    @Test
    void 크레딧_소진으로_막힌_쪽만_다시_큐에_들어간다() {
        // 1·2쪽 처리 완료, 3쪽은 오류로 막힘, 4·5쪽이 크레딧 소진
        givenFinishedJob(false, "COMPLETED", "NEEDS_REVIEW", "BLOCKED", "CREDIT", "CREDIT");
        redisTemplate.opsForValue().set("job:" + JOB + ":canceled", "1", Duration.ofHours(1));

        JobResponseDto.Resume result = service.resume(USER_ID.toString(), JOB);

        assertEquals(List.of(4, 5), result.resumedPages());
        assertEquals("IN_PROGRESS", result.status());
        verify(jobRepository).reopenJob(JOB);

        // DB: 재개한 쪽은 PENDING + 사유 지움, 나머지는 그대로
        assertEquals("PENDING", pages.get(3).getStatus());
        assertNull(pages.get(3).getBlockedReason());
        assertEquals("BLOCKED", pages.get(2).getStatus(), "오류로 막힌 쪽은 다시 돌리지 않는다(재변환 정책 미정)");
        assertEquals("COMPLETED", pages.get(0).getStatus(), "이미 처리한 쪽은 다시 돌리지 않는다 — 같은 쪽에 두 번 쓰지 않는다");

        // 큐: 4·5쪽 태스크가 원래 쪽 파일 경로로, 순서대로
        List<String> tasks = queue();
        assertEquals(2, tasks.size());
        assertTrue(tasks.get(0).contains("\"pageNo\":4") && tasks.get(0).contains("s3/" + JOB + "/page-4.pdf"));
        assertTrue(tasks.get(1).contains("\"pageNo\":5"));
        assertTrue(tasks.get(0).contains("\"userId\":\"" + USER_ID + "\""));

        // 상태 Hash: DB 기준으로 다시 썼고 만료가 없다(종료 1시간 TTL 해제)
        assertEquals("5", redisTemplate.opsForHash().get(hashKey(), "total_pages"));
        assertEquals("COMPLETED", redisTemplate.opsForHash().get(hashKey(), "page:1"));
        assertEquals("BLOCKED", redisTemplate.opsForHash().get(hashKey(), "page:3"));
        assertEquals("PENDING", redisTemplate.opsForHash().get(hashKey(), "page:4"));
        assertEquals(-1L, redisTemplate.getExpire(hashKey()));

        // 예전 취소 플래그가 남아 있으면 워커가 태스크를 버린다 — 지운다
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("job:" + JOB + ":canceled")));
    }

    @Test
    void 잔여_크레딧이_없으면_시작하지_않는다() {
        givenFinishedJob(false, "COMPLETED", "CREDIT");
        when(creditBalanceService.remaining(ORG_ID)).thenReturn(0L);

        CustomException e = assertThrows(CustomException.class, () -> service.resume(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.CREDIT_INSUFFICIENT, e.getErrorCode());
        assertNothingChanged();
    }

    @Test
    void 잔여가_1이면_보낸다_변환_관문과_같은_기준() {
        givenFinishedJob(false, "COMPLETED", "CREDIT");
        when(creditBalanceService.remaining(ORG_ID)).thenReturn(1L);

        assertEquals(List.of(2), service.resume(USER_ID.toString(), JOB).resumedPages());
    }

    @Test
    void 무소속_계정은_잔여를_보지_않는다() {
        givenFinishedJob(false, "CREDIT");
        when(jobRepository.findOrganizationIdById(JOB)).thenReturn(null);

        assertEquals(List.of(1), service.resume(USER_ID.toString(), JOB).resumedPages());
        verify(creditBalanceService, never()).remaining(any());
    }

    @Test
    void 받은_파일로_만든_사본은_거절한다() {
        givenFinishedJob(true, "COMPLETED", "CREDIT");

        CustomException e = assertThrows(CustomException.class, () -> service.resume(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.JOB_COPY_NOT_RESUMABLE, e.getErrorCode());
        assertNothingChanged();
    }

    @Test
    void 이어서_처리할_쪽이_없으면_거절한다() {
        givenFinishedJob(false, "COMPLETED", "BLOCKED");

        CustomException e = assertThrows(CustomException.class, () -> service.resume(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.JOB_NOTHING_TO_RESUME, e.getErrorCode());
        assertNothingChanged();
    }

    @Test
    void 진행_중이면_거절한다() {
        givenFinishedJob(false, "COMPLETED", "CREDIT");
        job.updateStatus("IN_PROGRESS");

        CustomException e = assertThrows(CustomException.class, () -> service.resume(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode());
        assertNothingChanged();
    }

    /** 동시에 두 번 누름 — 상태 전이에서 진 쪽은 쪽도 큐도 건드리지 않는다 */
    @Test
    void 동시_요청에서_진_쪽은_아무것도_바꾸지_않는다() {
        givenFinishedJob(false, "COMPLETED", "CREDIT");
        when(jobRepository.reopenJob(JOB)).thenReturn(0);

        CustomException e = assertThrows(CustomException.class, () -> service.resume(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode());
        assertEquals("BLOCKED", pages.get(1).getStatus());
        assertTrue(queue().isEmpty());
    }

    @Test
    void 남의_작업은_403() {
        when(jobRepository.findByIdAndUserId(eq(JOB), any())).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class, () -> service.resume(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    private void assertNothingChanged() {
        verify(jobRepository, never()).reopenJob(any());
        assertTrue(queue().isEmpty());
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(hashKey())));
        assertTrue(pages.stream().filter(p -> p.getBlockedReason() != null).allMatch(p -> "BLOCKED".equals(p.getStatus())));
    }

    // ── 실패한 쪽 다시 변환 ─────────────────────────────

    @Test
    void 다시_변환은_사유_없이_막힌_쪽만_다시_큐에_넣는다() {
        // 2·5쪽 AI 실패, 3쪽 크레딧 소진, 4쪽 취소
        givenFinishedJob(false, "COMPLETED", "BLOCKED", "CREDIT", "CANCELED", "BLOCKED");

        JobResponseDto.Retry result = service.retry(USER_ID.toString(), JOB);

        assertEquals(List.of(2, 5), result.retriedPages());
        assertEquals("IN_PROGRESS", result.status());
        verify(jobRepository).reopenJob(JOB);
        assertEquals("PENDING", pages.get(1).getStatus());
        assertEquals("PENDING", pages.get(4).getStatus());
        assertEquals(Page.BLOCKED_CREDIT_EXHAUSTED, pages.get(2).getBlockedReason(), "크레딧 소진 쪽은 이어서 처리가 맡는다");
        assertEquals(Page.BLOCKED_CANCELED, pages.get(3).getBlockedReason(), "취소한 쪽은 다시 돌리지 않는다");

        // AI 가 BLOCKED 로 답해 남은 옛 결과를 지운다 — 남기면 성공할 때 결과가 두 벌 생긴다
        verify(pageResultCleaner).deleteResultsOf(JOB, List.of(2, 5));

        List<String> tasks = queue();
        assertEquals(2, tasks.size());
        assertTrue(tasks.get(0).contains("\"pageNo\":2") && tasks.get(0).contains("s3/" + JOB + "/page-2.pdf"));
        assertTrue(tasks.get(1).contains("\"pageNo\":5"));
        assertEquals("PENDING", redisTemplate.opsForHash().get(hashKey(), "page:2"));
        assertEquals("BLOCKED", redisTemplate.opsForHash().get(hashKey(), "page:3"));
        assertEquals("BLOCKED", redisTemplate.opsForHash().get(hashKey(), "page:4"));
    }

    @Test
    void 다시_변환할_실패한_쪽이_없으면_JOB4016() {
        givenFinishedJob(false, "COMPLETED", "CREDIT", "CANCELED");

        CustomException e = assertThrows(CustomException.class, () -> service.retry(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.JOB_NOTHING_TO_RETRY, e.getErrorCode());
        assertNothingChanged();
        verify(pageResultCleaner, never()).deleteResultsOf(any(), any());
    }

    @Test
    void 다시_변환도_잔여_크레딧이_없으면_시작하지_않는다() {
        givenFinishedJob(false, "COMPLETED", "BLOCKED");
        when(creditBalanceService.remaining(ORG_ID)).thenReturn(0L);

        CustomException e = assertThrows(CustomException.class, () -> service.retry(USER_ID.toString(), JOB));
        assertEquals(ErrorCode.CREDIT_INSUFFICIENT, e.getErrorCode());
        assertNothingChanged();
        verify(pageResultCleaner, never()).deleteResultsOf(any(), any());
    }

    @Test
    void 다시_변환도_사본과_진행_중_작업은_거절한다() {
        givenFinishedJob(true, "COMPLETED", "BLOCKED");
        assertEquals(ErrorCode.JOB_COPY_NOT_RESUMABLE,
                assertThrows(CustomException.class, () -> service.retry(USER_ID.toString(), JOB)).getErrorCode());

        givenFinishedJob(false, "COMPLETED", "BLOCKED");
        job.updateStatus("IN_PROGRESS");
        assertEquals(ErrorCode.JOB_IN_PROGRESS,
                assertThrows(CustomException.class, () -> service.retry(USER_ID.toString(), JOB)).getErrorCode());
        assertNothingChanged();
    }
}
