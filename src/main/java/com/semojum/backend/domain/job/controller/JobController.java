package com.semojum.backend.domain.job.controller;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobService;
import com.semojum.backend.domain.job.service.JobOptionsService;
import com.semojum.backend.domain.job.service.JobStatusService;
import com.semojum.backend.domain.job.service.SseService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.service.JobCancelService;
import com.semojum.backend.domain.job.service.JobResumeService;
import com.semojum.backend.domain.job.service.JobSaveService;
import com.semojum.backend.global.client.ClientInfoResolver;
import jakarta.servlet.http.HttpServletRequest;

/** 변환 작업 API — 업로드 · 새 문서 · 설정 조회 · 최종 저장 · 상태 · 취소 · 이어서 처리 · 다시 변환 · SSE */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final JobOptionsService jobOptionsService;
    private final JobStatusService jobStatusService;
    private final ClientInfoResolver clientInfoResolver;
    private final SseService sseService;
    private final JobRepository jobRepository;
    private final JobCancelService jobCancelService;
    private final JobSaveService jobSaveService;
    private final JobResumeService jobResumeService;

    /**
     * 파일 업로드로 변환 작업 생성.
     *
     * <p><b>{@code file} 파트를 여러 개 보낼 수 있는 건 이미지뿐이다</b> — 한 장이 한 쪽이 되고
     * 쪽 순서는 보낸 순서다. PDF·HWP·HWPX·TXT는 하나만 받고, 섞어 보내면 COMMON4000.
     * 파트 이름은 그대로 {@code file}이라 한 장만 보내는 기존 호출은 바뀌지 않는다.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<JobResponseDto.Create> createJob(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestPart("file") List<MultipartFile> files,
            // 선택 — 서버가 파일 종류로 정한다(PDF·HWP·HWPX·이미지=c, TXT=b). 구 클라이언트 호환으로 받기만 한다
            @RequestParam(value = "mode", required = false) String mode,
            // 점자 판면 마지막 줄에 쪽번호를 넣을지 — 업로드 시 선택 (미전송 시 false)
            @RequestParam(value = "insertPageNumber", defaultValue = "false") boolean insertPageNumber,
            // 꼬리말(묵자, 최대 200자) — 서버는 보관만 하고 점역 · 배치는 앱이 한다. 미전송 시 없음
            @RequestParam(value = "footerText", required = false) String footerText,
            // 조판 옵션 — 폼 필드로 하나씩 받는다. 안 보낸 항목은 기본값(32칸×26줄·홀수 면 …)
            @RequestParam(value = "cellsPerLine", required = false) Integer cellsPerLine,
            @RequestParam(value = "linesPerPage", required = false) Integer linesPerPage,
            @RequestParam(value = "pageNumberLine", required = false) String pageNumberLine,
            @RequestParam(value = "coverPages", required = false) Integer coverPages,
            @RequestParam(value = "sourcePageStart", required = false) Integer sourcePageStart,
            @RequestParam(value = "braillePageStart", required = false) Integer braillePageStart,
            @RequestParam(value = "showSourcePageNumber", required = false) Boolean showSourcePageNumber,
            @RequestParam(value = "showBraillePageNumber", required = false) Boolean showBraillePageNumber,
            @RequestParam(value = "showChangeLine", required = false) Boolean showChangeLine,
            @RequestParam(value = "footerAlign", required = false) String footerAlign,
            @RequestParam(value = "editScope", required = false) String editScope,
            @RequestParam(value = "advancedAi", required = false) Boolean advancedAi,
            // 꼬리말 출력 면 odd | every — 안 보내면 양식 · 기본값(odd)
            @RequestParam(value = "footerPages", required = false) String footerPages,
            // 점역 양식 — 고르면 그 양식의 조판·점역 값이 바탕이 되고, 아래 개별 항목이 그 위에 덮인다.
            // 안 고르면 계정 기본 점역 옵션이 바탕이다
            @RequestParam(value = "formId", required = false) String formId,
            // 점역 옵션 — 「열기」 창에서 이 작업만 따로 바꾼 항목. 안 보낸 항목은 바탕 값 그대로
            @RequestParam(value = "hangulGrade", required = false) String hangulGrade,
            @RequestParam(value = "englishGrade", required = false) String englishGrade,
            @RequestParam(value = "brailleRule", required = false) String brailleRule,
            @RequestParam(value = "mixedRule", required = false) String mixedRule,
            @RequestParam(value = "consecutiveSpace", required = false) String consecutiveSpace,
            @RequestParam(value = "lineBreakUnit", required = false) String lineBreakUnit,
            @RequestParam(value = "choiceLayout", required = false) String choiceLayout,
            HttpServletRequest request
    ) throws Exception {
        LayoutOptions options = new LayoutOptions(cellsPerLine, linesPerPage, pageNumberLine, coverPages,
                sourcePageStart, braillePageStart, showSourcePageNumber, showBraillePageNumber,
                showChangeLine, footerAlign, editScope, advancedAi, footerPages);
        BrailleOptions brailleOptions =
                // 시각 자료 설명 유형은 폼 필드로 받지 않는다 — 구조가 있고 업로드 창에 없는 항목이라
                // 양식이나 계정 기본값에서 온다
                new BrailleOptions(hangulGrade, englishGrade, brailleRule,
                        mixedRule, consecutiveSpace, lineBreakUnit, choiceLayout, null);
        return ApiResponse.success(
                jobService.createJob(userDetails.getUsername(), files, mode, insertPageNumber, footerText,
                        options, formId, brailleOptions, clientInfoResolver.resolve(request)));
    }

    /**
     * 새 문서 — 파일 없이 작업을 만든다(파일 › 새 문서).
     *
     * <p>쪽 나누기 · 점역은 앱이 한다. 원본 패널이 없는 2패널 작업이 된다.
     */
    @PostMapping("/text")
    public ApiResponse<JobResponseDto.Create> createFromText(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody JobRequestDto.NewDocument request,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(jobService.createNewDocument(
                userDetails.getUsername(), request, clientInfoResolver.resolve(httpRequest)));
    }

    // 업로드 설정 조회 — 조판 · 점역 옵션 + 꼬리말. 에디터가 작업을 열 때 설정 복원용
    @GetMapping("/{jobId}/options")
    public ApiResponse<JobResponseDto.Options> getJobOptions(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobOptionsService.getJobOptions(userDetails.getUsername(), jobId));
    }

    /**
     * 작업 저장 — 유일한 저장 경로.
     *
     * <p>FE가 「저장」을 누를 때 한 번만 부른다(자동 저장 없음). 본문은 작업 최종 상태 전체 —
     * 조판 설정 · 점역 옵션 · 세 패널 · 줄 메모가 함께 들어온다.
     *
     * <p>쪽 단위 트랜잭션이라 일부만 실패할 수 있다. {@code failedPages}가 비어 있으면 전부 성공이다.
     * 한글 JSON은 7~8배 압축되므로 {@code Content-Encoding: gzip}을 권한다.
     */
    @PostMapping("/{jobId}/save")
    public ApiResponse<JobResponseDto.SaveResult> saveJob(
            @PathVariable String jobId,
            @Valid @RequestBody JobRequestDto.SaveJob request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobSaveService.save(userDetails.getUsername(), jobId, request));
    }

    @GetMapping("/{jobId}/status")
    public ApiResponse<JobResponseDto.Status> getJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobStatusService.getJobStatus(userDetails.getUsername(), jobId));
    }

    // 변환 취소 — 완료된 페이지까지만 남기고 중단. AI에 이미 들어간 페이지는 마무리 후 저장(취소는 수렴).
    // 이미 끝난 작업이면 멱등(canceled=false, 현재 상태 반환). 확정 여부는 status로 구분(IN_PROGRESS=인플라이트 마무리 중).
    @PostMapping("/{jobId}/cancel")
    public ApiResponse<Map<String, Object>> cancelJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobCancelService.cancel(userDetails.getUsername(), jobId));
    }

    // 이어서 처리 — 크레딧이 떨어져 멈춘 쪽만 다시 돌린다. 이미 처리한 쪽은 다시 돌리지 않는다
    @PostMapping("/{jobId}/resume")
    public ApiResponse<JobResponseDto.Resume> resumeJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobResumeService.resume(userDetails.getUsername(), jobId));
    }

    // 실패한 쪽 다시 변환 — AI 오류 · 시간 초과로 막힌 쪽만 다시 돌린다. 크레딧 소진 쪽은 resume, 취소한 쪽은 대상이 아니다
    @PostMapping("/{jobId}/retry")
    public ApiResponse<JobResponseDto.Retry> retryJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobResumeService.retry(userDetails.getUsername(), jobId));
    }

    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobEvents(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        return sseService.connect(jobId);
    }

}