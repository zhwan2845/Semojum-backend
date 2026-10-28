package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.semojum.backend.domain.app.dto.AppVersionDto;
import com.semojum.backend.domain.app.service.AppVersionService;
import java.util.List;

/**
 * 운영자 API — 앱 버전 등록 · 이력.
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAppVersionController {

    private final AdminAccessGuard adminAccessGuard;
    private final AppVersionService appVersionService;

    // ── 앱 버전 — 등록(새 행=이력 보존)·이력. 앱의 조회는 무인증 GET /api/public/app-version ──
    @PostMapping("/app-version")
    public ApiResponse<AppVersionDto.HistoryItem> registerAppVersion(
            @RequestBody @Valid AppVersionDto.Register request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(appVersionService.register(request));
    }

    @GetMapping("/app-versions")
    public ApiResponse<List<AppVersionDto.HistoryItem>> listAppVersions() {
        adminAccessGuard.check();
        return ApiResponse.success(appVersionService.listHistory());
    }
}
