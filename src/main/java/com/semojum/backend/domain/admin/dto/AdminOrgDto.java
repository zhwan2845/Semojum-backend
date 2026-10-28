package com.semojum.backend.domain.admin.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 기관·계정 통합 표 · 기관 정보 (운영자) */
public class AdminOrgDto {

    // ── 통합 목록 ──
    public record Orgs(String month, List<OrgRow> items) {}

    public record OrgRow(
            UUID orgId,
            String name,
            String code,
            String contractType,
            List<AccountRow> accounts,
            Subtotal subtotal
    ) {}

    public record AccountRow(
            String loginId,
            String alias,
            String role,
            String status,          // ACTIVE | INACTIVE(잠김)
            Instant lastLoginAt,
            long monthCredits
    ) {}

    public record Subtotal(
            int accountCount,
            long monthCredits,             // 소속 계정 전체 합 (소계 줄)
            Instant adminLastLoginAt       // 기관 관리자(ROLE_ORG_ADMIN) 기준 — 없으면 null
    ) {}

    // ── 기관 상세·수정 ──
    public record OrgDetail(
            UUID orgId,
            String name,
            String code,                   // 변경 불가 (loginId 프리픽스)
            String contractType,
            LocalDate contractStartedAt,
            LocalDate contractExpiresAt,
            long creditAllocated,
            long creditUsed,
            long creditRemaining,
            String receiptEmail,
            List<String> accountLoginIds
    ) {}

    // 부분 수정 — null인 필드는 변경 없음. 최소 하나는 있어야 함
    public record UpdateOrg(
            @Size(max = 100) String name,
            String contractType,                    // BASIC | STANDARD | PREMIUM | FREE | COUPON
            LocalDate contractStartedAt,
            LocalDate contractExpiresAt,
            @PositiveOrZero Long creditAllocated    // 기관의 크레딧 추가 요청 처리 = 이 값 상향
    ) {}

    public record DeleteOrgResult(UUID orgId, int lockedAccounts, int canceledJobs) {}

    public record DeleteAccountResult(String loginId, int canceledJobs) {}

    // ── 쿠폰 ──
    public record CreateCoupon(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Positive Long creditAmount,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn
    ) {}

    public record CouponItem(
            UUID id,
            String name,
            long creditAmount,
            long used,                 // 이 쿠폰 출처의 차감 합
            long remaining,
            LocalDate startsOn,
            LocalDate endsOn,
            String displayStatus       // SCHEDULED | ACTIVE | EXHAUSTED(소진) | ENDED(기간 종료)
    ) {}
}
