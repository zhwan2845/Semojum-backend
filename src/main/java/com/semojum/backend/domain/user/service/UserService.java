package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.semojum.backend.domain.job.service.FooterBrailleService;
import com.semojum.backend.domain.result.entity.PageMemo;
import com.semojum.backend.domain.result.repository.PageMemoReplyRepository;
import com.semojum.backend.domain.result.repository.PageMemoRepository;
import com.semojum.backend.domain.result.service.PageResultSerializer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import com.semojum.backend.domain.job.service.JobRedisKeys;

/**
 * 쪽 조회 · 진행 중 작업 — 앱에 남은 서버 조회는 쪽 하나와 재시작 복구용 진행 중 작업뿐이다.
 *
 * <p>원본 미리보기는 presigned URL(15분)이라 장기 캐시 금지 — {@code original.type}(image · pdf · text)으로 분기한다
 *
 * <p>쪽 결과 직렬화는 SSE와 같은 PageResultSerializer를 쓴다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final S3Service s3Service;
    private final PageResultRepository pageResultRepository;
    private final PageResultSerializer pageResultSerializer;
    private final FooterBrailleService footerBrailleService;
    private final PageMemoRepository pageMemoRepository;
    private final PageMemoReplyRepository memoReplyRepository;

    /** 진행 중 작업의 완료 페이지 비율(0~100). 그 외에는 null. */
    private Integer progressOf(Job job) {
        if (!job.isInProgress()) return null;
        try {
            Map<Object, Object> pages = redisTemplate.opsForHash().entries(JobRedisKeys.pages(job.getId()));
            if (pages.isEmpty()) return 0;
            int done = 0, total = 0;
            for (Map.Entry<Object, Object> e : pages.entrySet()) {
                if (JobRedisKeys.TOTAL_PAGES.equals(e.getKey())) continue;
                total++;
                if (Page.TERMINAL_STATUSES.contains(String.valueOf(e.getValue()))) done++;
            }
            return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        } catch (Exception e) {
            // 진행률은 부가 정보 — Redis 장애가 목록 조회 전체를 막지 않게 한다
            log.warn("진행률 조회 실패: jobId={}", job.getId(), e);
            return null;
        }
    }

    // 앱 재시작·네트워크 재연결 시 복구용: 아직 진행 중인 Job 목록.
    // FE는 이 목록으로 각 Job의 status를 조회하고 SSE를 다시 연결한다(탭 2개 이상 대응).
    @Transactional(readOnly = true)
    public List<JobResponseDto.ActiveJob> getActiveJobs(String userId) {
        UUID uid = UUID.fromString(userId);
        List<Job> jobs = jobRepository.findActiveByUserIdAndStatusIn(uid, List.of("PENDING", "IN_PROGRESS"));
        List<JobResponseDto.ActiveJob> result = new ArrayList<>();
        for (Job job : jobs) {
            result.add(new JobResponseDto.ActiveJob(
                    job.getId(),
                    job.getMode(),
                    job.getStatus(),
                    job.getTotalPages(),
                    progressOf(job),
                    job.getOriginalFileName(),
                    job.getLastModifiedAt(),
                    job.getLastEditedPage()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public JobResponseDto.JobDetail getJobPage(String userId, String jobId, int pageNo) {
        // 본인 Job인지 검증 (타인 jobId 직접 입력 시 403 반환)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 원본 미리보기 — image(렌더 JPEG) · pdf(렌더 실패 폴백) · text(b), URL은 presigned 15분
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        return toJobDetail(job, pageResult, page, pageNo);
    }

    /** 쪽 조회 응답 조립 — 본인 조회 · 운영자 열람이 같은 모양을 쓴다 */
    private JobResponseDto.JobDetail toJobDetail(Job job, PageResult pageResult, Page page, int pageNo) {
        return new JobResponseDto.JobDetail(
                job.getId(),
                job.getMode(),
                job.getStatus(),
                job.getTotalPages(),
                job.getFailedPages(),
                job.getOriginalFileName(),
                job.getStartedAt(),
                job.getFinishedAt(),
                pageNo,
                job.isInsertPageNumber(),
                job.resolveLayoutOptions(),
                footerBrailleService.resolve(job),
                pageResultSerializer.buildResult(pageResult),
                buildOriginal(job.getMode(), page),
                memosOf(page.getId())
        );
    }

    /** 그 쪽의 줄 메모. 없으면 빈 배열 — FE가 null 검사 없이 순회하도록 */
    private List<JobResponseDto.Memo> memosOf(UUID pageId) {
        var memos = pageMemoRepository.findByPageIdOrderByElementIdAscLineIndexAsc(pageId);
        // 답글은 쪽 단위로 한 번에 모은다 — 메모마다 조회하면 N+1
        Map<Long, List<JobResponseDto.MemoReply>> replies = new HashMap<>();
        if (!memos.isEmpty()) {
            for (var r : memoReplyRepository.findByMemoIdInOrderByIdAsc(
                    memos.stream().map(PageMemo::getId).toList())) {
                replies.computeIfAbsent(r.getMemoId(), k -> new ArrayList<>())
                        .add(new JobResponseDto.MemoReply(r.getId(), r.getAuthor(), r.getBody(), r.getCreatedAt()));
            }
        }
        return memos.stream()
                .map(m -> new JobResponseDto.Memo(
                        m.getId(), m.getPanel(), m.getElementId(), m.getLineIndex(),
                        m.getLineText(), m.getBody(), m.getCreatedAt(),
                        m.getAuthor(), m.isResolved(), m.getResolvedAt(),
                        replies.getOrDefault(m.getId(), List.of())))
                .toList();
    }

    // 운영자 열람(결과 미리보기) — 소유자 검증 없이 페이지 결과+원본 조회.
    // 접속·열람 범위는 운영자 전용(AdminMonitorController에서 AdminAccessGuard로 검증) — 편집·재변환은 없다.
    @Transactional(readOnly = true)
    public JobResponseDto.JobDetail getJobPageAsAdmin(String jobId, int pageNo) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        return toJobDetail(job, pageResult, page, pageNo);
    }

    // 원본 PDF presigned URL 수명 — FE는 페이지 진입 직후 1회 fetch하므로 짧아도 되지만,
    // 느린 회선에서 대용량 페이지를 받는 경우까지 감안해 15분. 만료 후엔 페이지 조회를 다시 호출하면 된다.
    private static final Duration ORIGINAL_URL_TTL = Duration.ofMinutes(15);

    // 모드별 원본 구성. pdfPath(s3:// 전체경로)를 가공 없이 그대로 S3Service에 넘긴다.
    private JobResponseDto.OriginalContent buildOriginal(String mode, Page page) {
        if ("b".equals(mode)) {
            // 텍스트 작업은 원본 파일을 받지 않는다(쪽도 첫 저장 때 생긴다) — 경로가 비면 원본이 없다
            if (page.getPdfPath() == null || page.getPdfPath().isBlank()) return null;
            // 예전(AI를 거치던 때) 텍스트 작업: S3의 .txt를 읽어 줄 단위 배열로. split("\n", -1)로 빈 줄 보존(trim/필터 금지).
            String text = new String(s3Service.downloadFile(page.getPdfPath()), StandardCharsets.UTF_8);
            List<String> lines = Arrays.asList(text.split("\n", -1));
            return new JobResponseDto.OriginalContent("text", null, lines);
        }
        // mode a, c: 미리 렌더한 이미지가 원칙(FE가 <img>로 바로 그린다 — 실측 pdf.js 1.8~2.9s → 6~9ms).
        // 렌더 실패·page-image 비활성으로 이미지가 없을 때만 원본 PDF로 내려 화면이 비지 않게 한다.
        if (page.getImagePath() != null) {
            return new JobResponseDto.OriginalContent(
                    "image", s3Service.getPresignedUrl(page.getImagePath(), ORIGINAL_URL_TTL), null);
        }
        return new JobResponseDto.OriginalContent(
                "pdf", s3Service.getPresignedUrl(page.getPdfPath(), ORIGINAL_URL_TTL), null);
    }

}
