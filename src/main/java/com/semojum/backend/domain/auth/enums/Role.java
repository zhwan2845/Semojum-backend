package com.semojum.backend.domain.auth.enums;

/**
 * 계정 역할 — Spring Security 권한으로 그대로 실린다(UserDetailsServiceImpl).
 *
 * <p>운영자 API({@code /api/admin/**})는 SecurityConfig의 {@code hasRole("ADMIN")} + {@code AdminAccessGuard}로 막는다
 *
 * <p>기관 관리자 API({@code /api/org/**})의 역할 검증은 서비스에서 한다(403)
 */
public enum Role {
    ROLE_ADMIN,      // 세모점 운영자 (운영자 콘솔)
    ROLE_ORG_ADMIN,  // 기관 관리자 — 앱의 「기관 관리」 화면에서 소속 계정 별칭 · 잠금 제어
    ROLE_USER        // 점역사
}
