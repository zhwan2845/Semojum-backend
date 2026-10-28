package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.billing.service.CreditBalanceService;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.semojum.backend.domain.result.service.PageResultCleaner;

/**
 * 이어서 처리 · 실패한 쪽 다시 변환 — 막힌 쪽을 다시 돌린다. 둘은 같은 길이고 대상 쪽만 다르다.
 *
 * <ul>
 *   <li>이어서 처리({@link #resume}): {@code blocked_reason = CREDIT_EXHAUSTED} 인 쪽 — AI 에 보내기 전에 막혀 크레딧이 빠진 적이 없다</li>
 *   <li>다시 변환({@link #retry}): 사유 없이 막힌 쪽(AI 오류 · 시간 초과 · 재시도 초과) — 실패한 쪽은 차감된 적이 없다</li>
 *   <li>이미 처리한 쪽은 대상이 아니므로 <b>같은 쪽에 두 번 차감하지 않는다</b></li>
 * </ul>
 *
 * <p>끝난 작업을 다시 IN_PROGRESS 로 돌리고 쪽을 큐에 넣으면, 이후는 처음 변환과 같은 길이다
 * (스케줄러 · 잔여 크레딧 관문 · 워커 · 종료 판정 · SSE). 잔여가 10 미만이면 관문이 한 쪽씩 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobResumeService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final JobDispatcher jobDispatcher;
    private final CreditBalanceService creditBalanceService;
    private final RedisTemplate<String, String> redisTemplate;
    private final PageResultCleaner pageResultCleaner;

    @Transactional
    public JobResponseDto.Resume resume(String userId, String jobId) {
        Job job = restartableJob(userId, jobId);
        if (!pageRepository.existsByJob_IdAndBlockedReason(jobId, Page.BLOCKED_CREDIT_EXHAUSTED)) {
            throw new CustomException(ErrorCode.JOB_NOTHING_TO_RESUME);
        }
        checkCreditAndReopen(jobId);

        List<Page> targets = pageRepository.findByJobAndBlockedReasonOrderByPageNoAsc(job, Page.BLOCKED_CREDIT_EXHAUSTED);
        List<Integer> pageNos = requeue(job, targets);
        log.info("이어서 처리: jobId={}, {}쪽 재개 {}", jobId, pageNos.size(), pageNos);
        return new JobResponseDto.Resume(jobId, "IN_PROGRESS", job.getTotalPages(), pageNos);
    }

    /**
     * 실패한 쪽 다시 변환 — AI 오류 · 시간 초과 · 재시도 초과로 막힌 쪽(사유 없음)만 다시 돌린다(「일부 쪽 변환 실패」 창).
     *
     * <p>크레딧 소진 쪽은 {@link #resume} 이, 취소한 쪽({@code CANCELED})은 사용자가 멈춘 것이라 대상이 아니다.
     * 실패한 쪽은 차감된 적이 없다(성공한 쪽만 차감) — 다시 돌려 성공하면 그때 처음 차감된다.
     *
     * <p>AI 가 BLOCKED 로 답한 쪽은 결과가 이미 저장돼 있다 — 지우지 않으면 다시 변환이 성공할 때
     * 같은 쪽에 결과가 두 벌 생겨 쪽 조회가 깨진다. ⚠️ 그 결과에 실린 원가 기록(실패한 시도의 GPU 사용)도 함께 지워진다.
     */
    @Transactional
    public JobResponseDto.Retry retry(String userId, String jobId) {
        Job job = restartableJob(userId, jobId);
        if (!pageRepository.existsByJobAndStatusAndBlockedReasonIsNull(job, "BLOCKED")) {
            throw new CustomException(ErrorCode.JOB_NOTHING_TO_RETRY);
        }
        checkCreditAndReopen(jobId);

        List<Page> targets = pageRepository.findByJobAndStatusAndBlockedReasonIsNullOrderByPageNoAsc(job, "BLOCKED");
        int stale = pageResultCleaner.deleteResultsOf(jobId, targets.stream().map(Page::getPageNo).toList());
        List<Integer> pageNos = requeue(job, targets);
        log.info("실패한 쪽 다시 변환: jobId={}, {}쪽 {} (옛 결과 {}건 삭제)", jobId, pageNos.size(), pageNos, stale);
        return new JobResponseDto.Retry(jobId, "IN_PROGRESS", job.getTotalPages(), pageNos);
    }

    /** 내 작업이고, 받은 파일의 사본이 아니고, 변환 중이 아니어야 다시 돌린다 */
    private Job restartableJob(String userId, String jobId) {
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        // 받은 .smj 로 만든 사본 — 변환이 아닌 작업(admin_copy, 통계 · 원가 제외)이라 여기서 변환을 돌리면 집계가 틀어진다
        if (job.isAdminCopy()) throw new CustomException(ErrorCode.JOB_COPY_NOT_RESUMABLE);
        if (job.isInProgress()) throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        return job;
    }

    private void checkCreditAndReopen(String jobId) {
        // 잔여가 모자라면 시작하지 않는다. 기준은 변환 관문과 같다 — 1 이상이면 보낸다. 무소속은 제한 없음
        UUID orgId = jobRepository.findOrganizationIdById(jobId);
        if (orgId != null && creditBalanceService.remaining(orgId) <= 0) {
            throw new CustomException(ErrorCode.CREDIT_INSUFFICIENT);
        }
        // 상태 전이가 곧 동시 요청 관문이다 — 두 번째 요청은 행 잠금을 기다렸다가 0 을 받는다
        if (jobRepository.reopenJob(jobId) == 0) throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
    }

    /** 대상 쪽을 PENDING 으로 되돌리고 상태 Hash 를 다시 쓴 뒤 큐에 넣는다. 넣은 쪽 번호를 돌려준다 */
    private List<Integer> requeue(Job job, List<Page> targets) {
        String jobId = job.getId();
        targets.forEach(Page::requeue);

        // 상태 Hash 를 DB 기준으로 다시 쓴다 — 종료 1시간 뒤면 이미 사라졌고, 남아 있어도 BLOCKED 를 PENDING 으로 바꿔야 한다.
        // 쪽 순서대로 내보내는 SSE 커서가 이 Hash 를 본다
        String hashKey = JobRedisKeys.pages(jobId);
        redisTemplate.delete(hashKey);
        redisTemplate.opsForHash().put(hashKey, JobRedisKeys.TOTAL_PAGES, String.valueOf(job.getTotalPages()));
        for (Page page : pageRepository.findByJob(job)) {
            redisTemplate.opsForHash().put(hashKey, JobRedisKeys.pageField(page.getPageNo()), page.getStatus());
        }
        // 예전에 취소했던 작업이면 플래그가 남아 있을 수 있다(TTL 1h) — 남아 있으면 워커가 태스크를 버린다
        redisTemplate.delete(JobRedisKeys.cancelFlag(jobId));

        List<String> tasks = new ArrayList<>();
        List<Integer> pageNos = new ArrayList<>();
        for (Page page : targets) {
            tasks.add(JobService.taskJson(jobId, page.getPageNo(), page.getPdfPath(), job.getMode(), job.getTotalPages(),
                    job.getUser().getId(), job.resolveLayoutOptions().advancedAi(), job.resolveBrailleOptions()));
            pageNos.add(page.getPageNo());
        }
        // 커밋 후 적재(enqueueJob 이 보장) — 커밋 전에 워커가 집으면 아직 BLOCKED 인 쪽을 본다
        jobDispatcher.enqueueJob(job.getUser().getId().toString(), jobId, tasks);
        return pageNos;
    }
}
