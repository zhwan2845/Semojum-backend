package com.semojum.backend.domain.auth.service;

import com.semojum.backend.domain.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** JWT 인증 때 계정을 읽어 권한(role)을 싣는다 — JwtFilter가 부른다 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        // 계정 role을 그대로 권한으로. loginId는 액세스 로그의 user= 표기용
        return userRepository.findById(UUID.fromString(userId))
                .map(user -> new AuthUser(user.getId().toString(), user.getLoginId(), user.getRole().name()))
                .orElseThrow(() -> new UsernameNotFoundException("유저를 찾을 수 없습니다: " + userId));
    }
}