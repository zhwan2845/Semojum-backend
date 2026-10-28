package com.semojum.backend.domain.admin;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 운영자 API 접근 검증 — 운영자 콘솔(WEB) 계정만 통과, 앱 관리자(APP) · 비운영자 · 무인증은 COMMON4003 */
class AdminAccessGuardTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminAccessGuard guard = new AdminAccessGuard(userRepository);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(String role, String adminScope) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority(role))));
        User user = User.builder().loginId("admin").password("x").build();
        ReflectionTestUtils.setField(user, "adminScope", adminScope);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private void assertForbidden() {
        CustomException e = assertThrows(CustomException.class, guard::check);
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 웹_관리자는_통과한다() {
        login("ROLE_ADMIN", "WEB");
        assertDoesNotThrow(guard::check);
        assertEquals(userId.toString(), guard.currentUserId());
    }

    @Test
    void 범위가_없는_운영자도_통과한다() {
        login("ROLE_ADMIN", null);
        assertDoesNotThrow(guard::check);
    }

    @Test
    void 앱_관리자는_막는다() {
        login("ROLE_ADMIN", User.ADMIN_SCOPE_APP);
        assertForbidden();
    }

    @Test
    void 운영자가_아니면_막는다() {
        login("ROLE_USER", null);
        assertForbidden();
    }

    @Test
    void 인증이_없으면_막는다() {
        assertForbidden();
    }
}
