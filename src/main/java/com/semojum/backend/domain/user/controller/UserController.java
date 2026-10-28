package com.semojum.backend.domain.user.controller;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.user.dto.BrailleFormDto;
import com.semojum.backend.domain.user.service.UserService;
import jakarta.validation.Valid;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.semojum.backend.domain.user.dto.DefaultOptions;
import com.semojum.backend.domain.user.dto.OutputOptions;
import com.semojum.backend.domain.user.service.BrailleFormService;
import com.semojum.backend.domain.user.service.BrailleOptionsService;
import com.semojum.backend.domain.user.service.OutputOptionsService;

/**
 * 계정 설정 · 진행 중 작업 · 쪽 조회.
 *
 * <p><b>작업 목록은 없다</b> — 작업물은 앱이 로컬 {@code .smj}로 보관한다.
 * {@code /jobs/**} 두 경로는 목록이 아니다 — 하나는 앱 재시작 복구용, 하나는 에디터가 쪽을 읽는 경로다.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final BrailleOptionsService brailleOptionsService;
    private final BrailleFormService brailleFormService;
    private final OutputOptionsService outputOptionsService;

    /**
     * 내 기본 점역 옵션 조회 — 설정 › 기본 점역 옵션 설정 창을 열 때.
     *
     * <p>저장한 적 없으면 공장 기본값이 내려온다. 작업이 아니라 계정에 딸린 값이다.
     */
    @GetMapping("/braille-options")
    public ApiResponse<DefaultOptions> getBrailleOptions(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(brailleOptionsService.get(userDetails.getUsername()));
    }

    /**
     * 점역 양식 목록 — 기본 양식 5종 + 내가 만든 양식을 한 목록에.
     *
     * <p>기본 점역 옵션 설정의 「점역 양식」 드롭다운 · 점역 양식 창의 「양식 고르기」 · 사용자 양식 설정의 「바탕 양식」이 모두 이 하나를 쓴다.
     * <b>값 전체를 담아 보낸다</b> — 점역 양식 창의 읽기 전용 요약과 사용자 양식 설정의 「바탕 값으로」가 추가 호출 없이
     * 동작해야 한다. 양식 수가 열 개 안팎이라 응답이 작다.
     */
    @GetMapping("/braille-forms")
    public ApiResponse<BrailleFormDto.FormList> listBrailleForms(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(brailleFormService.list(userDetails.getUsername()));
    }

    /** 새 양식 저장 — 점역 양식 창 「새 양식 저장」. 같은 이름이 있으면 COMMON4009 */
    @PostMapping("/braille-forms")
    public ApiResponse<BrailleFormDto.Form> createBrailleForm(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BrailleFormDto.SaveForm request
    ) {
        return ApiResponse.success(brailleFormService.create(userDetails.getUsername(), request));
    }

    /**
     * 양식 수정 — 사용자 양식 설정 「저장」. 통째로 덮어쓴다.
     *
     * <p>기본 양식(builtin:)은 COMMON4003 — 되돌릴 길이 없어 손대지 못하게 한다.
     */
    @PatchMapping("/braille-forms/{formId}")
    public ApiResponse<BrailleFormDto.Form> updateBrailleForm(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String formId,
            @Valid @RequestBody BrailleFormDto.SaveForm request
    ) {
        return ApiResponse.success(brailleFormService.update(userDetails.getUsername(), formId, request));
    }

    /** 양식 삭제 — 사용자 양식 설정 「사용자 양식 지우기」. 이미 만든 작업은 값을 복사해 갔으므로 영향이 없다 */
    @DeleteMapping("/braille-forms/{formId}")
    public ApiResponse<Void> deleteBrailleForm(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String formId
    ) {
        brailleFormService.delete(userDetails.getUsername(), formId);
        return ApiResponse.success(null);
    }

    /**
     * 기본 점역 옵션 저장 — 기본 점역 옵션 설정 「확인」.
     *
     * <p>창이 항목 전부를 보여주므로 <b>통째로 덮어쓴다</b>(PATCH가 아니라 PUT). 빠진 항목은
     * 기본값으로 채우고, 값이 enum 밖이면 COMMON4000 — 오타가 저장돼 FE 점역 엔진에서
     * 조용히 무시되는 것보다 여기서 막는 편이 낫다.
     */
    @PutMapping("/braille-options")
    public ApiResponse<DefaultOptions> updateBrailleOptions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) DefaultOptions request
    ) {
        return ApiResponse.success(brailleOptionsService.update(userDetails.getUsername(), request));
    }

    /**
     * 내 출력용 점자문서 설정 조회 — 설정 › 출력용 점자문서 페이지설정 · 문서 최적화 창이 열 때.
     * 저장한 적 없으면 공장 기본값이 내려온다. 작업이 아니라 계정에 딸린 값이다.
     */
    @GetMapping("/output-options")
    public ApiResponse<OutputOptions> getOutputOptions(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(outputOptionsService.get(userDetails.getUsername()));
    }

    /**
     * 출력용 점자문서 설정 저장 — 창의 「설정 저장(기본값으로 저장)」. 통째로 덮어쓴다(PUT).
     * 빠진 항목은 기본값, 범위 밖 값은 COMMON4000.
     */
    @PutMapping("/output-options")
    public ApiResponse<OutputOptions> updateOutputOptions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) OutputOptions request
    ) {
        return ApiResponse.success(outputOptionsService.update(userDetails.getUsername(), request));
    }

    // 앱 재시작·네트워크 재연결 시 복구용 — 진행 중(PENDING/IN_PROGRESS) Job 목록.
    // FE는 lastModifiedAt이 가장 최신인 작업의 lastEditedPage로 이동한다.
    @GetMapping("/jobs/active")
    public ApiResponse<List<JobResponseDto.ActiveJob>> getActiveJobs(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(userService.getActiveJobs(userDetails.getUsername()));
    }

    @GetMapping("/jobs/{jobId}/pages/{pageNo}")
    public ApiResponse<JobResponseDto.JobDetail> getJobPage(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(userService.getJobPage(userDetails.getUsername(), jobId, pageNo));
    }
}
