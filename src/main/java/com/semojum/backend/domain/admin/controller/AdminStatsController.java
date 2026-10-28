package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.domain.admin.dto.AdminStatsDto;
import com.semojum.backend.domain.admin.service.AdminStatsService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 운영자 API — 통계 (대표 · 상세).
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminAccessGuard adminAccessGuard;
    private final AdminStatsService adminStatsService;

    @GetMapping("/stats/overview")
    public ApiResponse<AdminStatsDto.Overview> getStatsOverview(
            @RequestParam(required = false) String period
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminStatsService.getOverview(period));
    }

    @GetMapping("/stats/workload")
    public ApiResponse<AdminStatsDto.Workload> getStatsWorkload(
            @RequestParam(required = false) String unit
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminStatsService.getWorkload(unit));
    }

    // 기관별 수익성 — 차액 = 환산 매출(차감 크레딧 × creditPriceKrw) − 원가. 단가는 관리 변수
    @GetMapping("/stats/profitability")
    public ApiResponse<AdminStatsDto.Profitability> getStatsProfitability(
            @RequestParam(required = false) String month
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminStatsService.getProfitability(month));
    }

    @GetMapping("/stats/layout-cost")
    public ApiResponse<AdminStatsDto.LayoutCost> getStatsLayoutCost(
            @RequestParam(required = false) String month
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminStatsService.getLayoutCost(month));
    }
}
