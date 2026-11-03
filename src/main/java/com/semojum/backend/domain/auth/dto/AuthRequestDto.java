package com.semojum.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthRequestDto {

    // 운영자가 발급한 계정(loginId)으로만 로그인
    /** @param keepLoggedIn 로그인 창 「로그인 상태 유지」 — true면 리프레시 토큰이 30일(기본 12시간). 안 보내면 false */
    public record Login(
            @NotBlank String loginId,
            @NotBlank String password,
            Boolean keepLoggedIn
    ) {
        public Login(String loginId, String password) {
            this(loginId, password, null);
        }
    }

    public record Refresh(
            @NotBlank String refreshToken
    ) {}
}
