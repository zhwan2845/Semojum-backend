package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.domain.billing.dto.PricingDto;
import com.semojum.backend.domain.billing.service.PricingAdminService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 운영자 API — 단가 · 배율 관리 변수.
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminPricingController {

    private final AdminAccessGuard adminAccessGuard;
    private final PricingAdminService pricingAdminService;

    // 단가·배율 관리 변수 — 현재(최신) 판 조회
    @GetMapping("/pricing")
    public ApiResponse<PricingDto.Response> getPricing() {
        adminAccessGuard.check();
        return ApiResponse.success(pricingAdminService.getCurrent());
    }

    // 단가·배율 관리 변수 — 새 판 등록 (config 전문 교체, 과거 판은 이력으로 보존)
    @PutMapping("/pricing")
    public ApiResponse<PricingDto.Response> updatePricing(
            @RequestBody @Valid PricingDto.Update request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(pricingAdminService.update(request));
    }
}
