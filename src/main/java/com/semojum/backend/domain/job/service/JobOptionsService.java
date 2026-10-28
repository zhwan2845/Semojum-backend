package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 하나의 업로드 설정(조판 · 점역 옵션 · 꼬리말) 조회 · 수정. 수정은 최종 저장({@link JobSaveService})이 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOptionsService {

    private final JobRepository jobRepository;
    private final FooterBrailleService footerBrailleService;

    /**
     * 이 작업의 업로드 설정 조회 — 조판 옵션 + 꼬리말.
     *
     * <p>옵션 없이 만들어진 기존 작업도 기본값이 채워진 완전한 형태를 준다(resolveLayoutOptions).
     * 페이지 조회와 달리 <b>변환 결과가 없어도</b> 볼 수 있다 — 설정은 업로드 시점에 확정되므로
     * 변환 중·실패한 작업에서도 필요하다.
     */
    @Transactional(readOnly = true)
    public JobResponseDto.Options getJobOptions(String userId, String jobId) {
        // 타인 작업이면 403 (페이지 조회와 같은 기준)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        return new JobResponseDto.Options(
                job.getId(), job.isInsertPageNumber(), job.getFooterText(),
                footerBrailleService.resolve(job), job.resolveLayoutOptions(), job.resolveBrailleOptions());
    }

    /**
     * 작업 설정 변경 — 최종 저장({@link JobSaveService})이 부른다(별도 API 없음).
     *
     * <p><b>보낸 항목만 바뀐다</b>({@link LayoutOptions#merge}). 꼬리말만 {@code ""}로 지울 수 있다.
     *
     * <p>꼬리말:
     * <ul>
     *   <li>묵자가 <b>바뀌면</b> 예전 점역 결과를 비운다 — 서버는 점역하지 않는다(앱이 점역)</li>
     *   <li>묵자가 그대로여도 <b>판면이 좁아지면</b>(칸 수↓·쪽 번호 켜기) 예전 점자가 페이지행에 안 들어갈 수
     *       있다 — 길이만 다시 검사한다. 이 검사가 없으면 라이브러리가 말없이 뒤에서 자른다</li>
     *   <li>꼬리말을 지우면 점역 결과도 함께 비운다</li>
     * </ul>
     *
     * <p><b>변환 중에는 거부한다(JOB4010).</b> 조판 옵션 자체는 워커가 안 쓰지만 {@code advancedAi}가
     * gRPC 요청에 실려, 중간에 바뀌면 쪽마다 다른 설정으로 처리된 문서가 나온다.
     * ⚠️ 이미 변환이 끝난 작업에서 {@code advancedAi}를 바꿔도 <b>결과는 달라지지 않는다</b> —
     * 재변환 경로가 없어 기록만 남는다(저장 본문에 실려 오므로 거부하지 않고 받아 둔다).
     */
    @Transactional
    public JobResponseDto.Options updateJobOptions(String userId, String jobId,
                                                   JobRequestDto.UpdateOptions request) {
        // 타인 작업이면 403 (조회와 같은 기준 — 존재 여부를 숨긴다)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        if (job.isInProgress()) {
            throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        }

        LayoutOptions merged = job.resolveLayoutOptions().merge(request.layoutOptions()).validated();
        boolean insertPageNumber = request.insertPageNumber() != null
                ? request.insertPageNumber() : job.isInsertPageNumber();

        // 꼬리말: 미전송(null)이면 그대로, ""면 삭제, 값이 있으면 교체
        String footerText = job.getFooterText();
        if (request.footerText() != null) {
            String trimmed = request.footerText().trim();
            footerText = trimmed.isEmpty() ? null : trimmed;
            if (footerText != null && footerText.length() > 200) {
                throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            }
        }

        String footerBraille;
        if (footerText == null) {
            footerBraille = null;                                   // 지웠다 — 점역 결과도 함께 비운다
        } else if (footerText.equals(job.getFooterText()) && job.getFooterBraille() != null) {
            footerBraille = job.getFooterBraille();                 // 묵자 그대로 — 새 판면에 들어가는지만 본다
            footerBrailleService.validateFits(footerBraille, merged, job.getTotalPages());
        } else {
            // 바뀌었거나 점역 값이 없다 — 서버는 점역하지 않으므로 비운다(앱이 점역한다).
            // 예전 점자를 남겨 두면 새 묵자와 어긋난 꼬리말이 화면에 뜬다
            footerBraille = null;
        }

        // 점역 옵션도 같은 규칙 — 보낸 항목만 덮는다. 조판과 달리 판면 검증이 없어 값 검증만 한다
        BrailleOptions braille = job.resolveBrailleOptions().merge(request.brailleOptions()).validated();

        job.updateOptions(insertPageNumber, footerText, merged);
        job.updateBrailleOptions(braille);
        job.updateFooterBraille(footerBraille);
        log.info("조판 설정 변경: jobId={}, 꼬리말={}, 판면={}칸×{}줄",
                jobId, footerText == null ? "(없음)" : footerText, merged.cellsPerLine(), merged.linesPerPage());
        return new JobResponseDto.Options(jobId, insertPageNumber, footerText, footerBraille, merged, braille);
    }
}
