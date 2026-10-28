package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.domain.admin.service.AdminService;
import com.semojum.backend.domain.admin.dto.AdminOrgDto;
import com.semojum.backend.domain.admin.service.AdminOrgManageService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 운영자 API — 계정 발급 · 상태 · 역할 · 비밀번호 재발급 · 삭제.
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminAccessGuard adminAccessGuard;
    private final AdminService adminService;
    private final AdminOrgManageService adminOrgManageService;

    // 계정 삭제(소프트) — 잠금 + 삭제 표식, 작업물 보관
    @DeleteMapping("/accounts/{loginId}")
    public ApiResponse<AdminOrgDto.DeleteAccountResult> deleteAccount(
            @PathVariable String loginId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminOrgManageService.deleteAccount(loginId));
    }
    @PostMapping("/accounts")
    public ApiResponse<AdminResponseDto.IssuedAccounts> issueAccounts(
            @RequestBody @Valid AdminRequestDto.IssueAccounts request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminService.issueAccounts(request));
    }

    @PatchMapping("/accounts/{loginId}/status")
    public ApiResponse<AdminResponseDto.AccountStatus> updateAccountStatus(
            @PathVariable String loginId,
            @RequestBody @Valid AdminRequestDto.UpdateStatus request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminService.updateStatus(loginId, request));
    }

    @PatchMapping("/accounts/{loginId}/role")
    public ApiResponse<AdminResponseDto.AccountRole> updateAccountRole(
            @PathVariable String loginId,
            @RequestBody @Valid AdminRequestDto.UpdateRole request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminService.updateRole(loginId, request));
    }

    @PostMapping("/accounts/{loginId}/password-reissue")
    public ApiResponse<AdminResponseDto.IssuedAccount> reissuePassword(
            @PathVariable String loginId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminService.reissuePassword(loginId));
    }
}
