package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.domain.admin.dto.AdminMonitorDto;
import com.semojum.backend.domain.admin.service.AdminMonitorService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.user.service.UserService;

/**
 * 운영자 API — 실시간 모니터링 · 작업 상세 · 결과 미리보기.
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMonitorController {

    private final AdminAccessGuard adminAccessGuard;
    private final AdminMonitorService adminMonitorService;
    private final UserService userService;

    // ── 실시간 모니터링 — 전 기관 최근 작업, 10초 폴링 ──
    @GetMapping("/jobs")
    public ApiResponse<AdminMonitorDto.Jobs> listMonitorJobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer hours,
            @RequestParam(required = false) Integer size
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminMonitorService.listJobs(status, hours, size));
    }

    // ── 결과 미리보기 — 페이지 결과+원본(presigned). 확인용 — 편집·재변환 없음 ──
    @GetMapping("/jobs/{jobId}/pages/{pageNo}")
    public ApiResponse<JobResponseDto.JobDetail> getMonitorJobPage(
            @PathVariable String jobId,
            @PathVariable int pageNo
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(userService.getJobPageAsAdmin(jobId, pageNo));
    }

    // ── 작업 상세 — 요청 정보(접속 메타데이터)·처리 비용·쪽별 결과 ──
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<AdminMonitorDto.JobDetail> getMonitorJobDetail(
            @PathVariable String jobId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminMonitorService.getJobDetail(jobId));
    }
}
