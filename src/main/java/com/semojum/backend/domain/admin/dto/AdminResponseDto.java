package com.semojum.backend.domain.admin.dto;

import java.util.List;

public class AdminResponseDto {

    public record Org(
            String organizationId,
            String name,
            String code,
            String contractType
    ) {}

    // 발급/재발급 응답 — 비밀번호는 이 응답에서 1회만 노출 (서버는 BCrypt 해시만 보관).
    // role: 자동 생성되는 기관 관리자(00)와 점역사를 구분해 보여 준다
    public record IssuedAccount(
            String loginId,
            String password,
            String role
    ) {}

    public record IssuedAccounts(
            List<IssuedAccount> accounts
    ) {}

    public record AccountStatus(
            String loginId,
            String status
    ) {}

    public record AccountRole(
            String loginId,
            String role
    ) {}
}
