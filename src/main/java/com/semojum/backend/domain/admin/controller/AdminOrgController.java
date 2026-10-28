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
import java.util.List;
import java.util.UUID;

/**
 * 운영자 API — 기관 (기관·계정 통합 표 · 기관 정보 · 쿠폰).
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOrgController {

    private final AdminAccessGuard adminAccessGuard;
    private final AdminService adminService;
    private final AdminOrgManageService adminOrgManageService;

    // ── 기관·계정 통합 표 — 기관별 계정 + 소계(월 사용량·관리자 마지막 로그인) ──
    @GetMapping("/orgs")
    public ApiResponse<AdminOrgDto.Orgs> listOrgs(
            @RequestParam(required = false) String month
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminOrgManageService.listOrgs(month));
    }

    // ── 기관 정보 ──
    @GetMapping("/orgs/{orgId}")
    public ApiResponse<AdminOrgDto.OrgDetail> getOrg(
            @PathVariable UUID orgId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminOrgManageService.getOrg(orgId));
    }

    // 이름·계약(구분/기간)·할당 크레딧 수정 — 기관의 크레딧 추가 요청 처리 = creditAllocated 상향
    @PatchMapping("/orgs/{orgId}")
    public ApiResponse<AdminOrgDto.OrgDetail> updateOrg(
            @PathVariable UUID orgId,
            @RequestBody @Valid AdminOrgDto.UpdateOrg request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminOrgManageService.updateOrg(orgId, request));
    }

    // 쿠폰 발급·목록 — 차감은 쿠폰 잔량부터, 소진되면 계약 크레딧
    @PostMapping("/orgs/{orgId}/coupons")
    public ApiResponse<AdminOrgDto.CouponItem> issueCoupon(
            @PathVariable UUID orgId,
            @RequestBody @Valid AdminOrgDto.CreateCoupon request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminOrgManageService.issueCoupon(orgId, request));
    }

    @GetMapping("/orgs/{orgId}/coupons")
    public ApiResponse<List<AdminOrgDto.CouponItem>> listCoupons(
            @PathVariable UUID orgId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminOrgManageService.listCoupons(orgId));
    }

    // 기관 삭제(소프트) — 소속 계정 전부 잠금. 실삭제는 하지 않는다(보관 기간 정책 미정)
    @DeleteMapping("/orgs/{orgId}")
    public ApiResponse<AdminOrgDto.DeleteOrgResult> deleteOrg(
            @PathVariable UUID orgId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminOrgManageService.deleteOrg(orgId));
    }
    @PostMapping("/orgs")
    public ApiResponse<AdminResponseDto.Org> createOrg(
            @RequestBody @Valid AdminRequestDto.CreateOrg request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminService.createOrganization(request));
    }
}
