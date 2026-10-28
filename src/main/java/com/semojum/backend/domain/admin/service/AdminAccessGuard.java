package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 운영자 API 접근 검증 — {@code /api/admin} 핸들러가 모두 첫 줄에서 {@link #check()}를 부른다.
 *
 * <ul>
 *   <li>JWT(ROLE_ADMIN) 전용 — 감사는 액세스 로그의 user= 필드가 담당한다</li>
 *   <li>SecurityConfig의 hasRole과 이중 방어. 앱 관리자(admin_scope=APP)는 에디터 전용이라 운영자 API도 차단</li>
 *   <li>⚠️ 새 운영자 핸들러도 첫 줄에서 부를 것 — {@code AdminAccessGuardCoverageTest}가 빠진 곳을 잡는다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AdminAccessGuard {

    private final UserRepository userRepository;

    public String currentUserId() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public void check() {
        var auth = SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
        User user = userRepository
                .findById(UUID.fromString(auth.getName())).orElse(null);
        if (user != null && User.ADMIN_SCOPE_APP
                .equals(user.getAdminScope())) {
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
    }
}
