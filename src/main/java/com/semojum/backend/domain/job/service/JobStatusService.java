package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 변환 진행 상태 조회 — SSE를 못 쓰는 클라이언트의 폴링 대체 · 앱 재시작 복구용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobStatusService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final JobDispatcher jobDispatcher;

    // job 상태 조회 (Redis Hash에서 페이지별 상태 조회, 끝나고 Hash가 사라졌으면 DB)
    public JobResponseDto.Status getJobStatus(String userId, String jobId) {
        // 내 작업인지 먼저 — 남의 작업이면 상태도 주지 않고 스케줄러 우선순위(FG 리스)도 올리지 않는다.
        // SSE · 설정 조회와 같은 규칙이다
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        Map<Object, Object> redisData = redisTemplate.opsForHash().entries(JobRedisKeys.pages(jobId));

        // 상태 Hash 는 작업이 끝나고 1시간 뒤 사라진다 — 그 뒤엔 DB 로 답한다.
        // 다음 날 문서를 열어도 앱이 「크레딧 소진 (47/128쪽 완료)」 알림 띠를 띄울 수 있어야 한다.
        // DB 에는 RUNNING 이 없지만(Redis 전용) 끝난 작업이라 상관없다
        boolean fromDb = redisData.isEmpty();
        if (fromDb) {
            redisData = statesFromDb(job);
        } else {
            // 상태를 폴링한다 = 사용자가 보고 있다 → FG 리스 갱신 (공정 스케줄러 우선순위)
            jobDispatcher.touchForeground(jobId);
        }

        int totalPages = Integer.parseInt((String) redisData.get(JobRedisKeys.TOTAL_PAGES));
        // 끝난 쪽 수 — BLOCKED(실패)도 포함한다. 진행률의 분자라서 실패 쪽을 빼면
        // 실패가 섞인 작업은 분자가 total에 영원히 못 닿아 화면이 "진행 중"에 멈춘다.
        // 이름이 내용과 어긋나지만 이미 나간 응답 필드라 의미를 흔들지 않는다.
        int completedPages = 0;
        // 그중 결과가 나온 쪽(COMPLETED·NEEDS_REVIEW) — 종료 판정에만 쓴다
        int succeededPages = 0;
        int pendingPages = 0;
        int runningPages = 0;
        Map<String, String> pages = new HashMap<>();

        for (Map.Entry<Object, Object> entry : redisData.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();

            if (key.equals(JobRedisKeys.TOTAL_PAGES)) continue;

            pages.put(key, value);

            switch (value) {
                case "COMPLETED", "NEEDS_REVIEW" -> { completedPages++; succeededPages++; }
                case "BLOCKED" -> completedPages++;   // 끝났지만 성공은 아니다
                case "RUNNING" -> runningPages++;
                case "PENDING" -> pendingPages++;
            }
        }

        // 전체 job 상태 계산 — 판정 규칙은 ResultService.evaluateJobTermination과 같아야 한다.
        // 전 쪽이 terminal일 때 성공 0건이면 FAILED다. 예전엔 BLOCKED를 성공과 묶어 세는 바람에
        // 취소로 전 쪽이 BLOCKED된 작업을 COMPLETED라고 답했고, 같은 작업을 DB로 읽는
        // 운영자 화면은 FAILED라 서로 어긋났다(실측).
        // DB 상태가 정본이다 — 텍스트 작업(쪽 상태 SAVED)처럼 쪽 수로 판정할 수 없는 작업도 있다
        String overallStatus = fromDb ? job.getStatus()
                : overallStatus(totalPages, completedPages, succeededPages, runningPages);

        boolean creditExhausted = pageRepository.existsByJob_IdAndBlockedReason(jobId, Page.BLOCKED_CREDIT_EXHAUSTED);
        return new JobResponseDto.Status(jobId, totalPages, completedPages, pendingPages, runningPages, overallStatus, pages,
                creditExhausted);
    }

    /** 상태 Hash 와 같은 모양으로 DB 의 쪽 상태를 읽는다. DB 에는 RUNNING 이 없다(Redis 전용) */
    private Map<Object, Object> statesFromDb(Job job) {
        Map<Object, Object> states = new HashMap<>();
        states.put(JobRedisKeys.TOTAL_PAGES, String.valueOf(job.getTotalPages()));
        for (Page page : pageRepository.findByJob(job)) {
            states.put(JobRedisKeys.pageField(page.getPageNo()), page.getStatus());
        }
        return states;
    }

    /** 쪽 수로 판정한 전체 상태 — 전 쪽이 끝났는데 성공이 0이면 FAILED (ResultService.evaluateJobTermination 과 같은 규칙) */
    static String overallStatus(int totalPages, int completedPages, int succeededPages, int runningPages) {
        if (completedPages == totalPages) return succeededPages > 0 ? "COMPLETED" : "FAILED";
        if (runningPages > 0 || completedPages > 0) return "IN_PROGRESS";
        return "PENDING";
    }
}
