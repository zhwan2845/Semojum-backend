package com.semojum.backend.domain.org.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Email;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 기관 관리 (ROLE_ORG_ADMIN) 응답·요청 */
public class OrgDto {

    public record Dashboard(
            String orgName,
            String orgCode,
            String contractType,            // BASIC | STANDARD | PREMIUM | FREE | COUPON
            LocalDate contractStartedAt,
            LocalDate contractExpiresAt,
            long creditAllocated,
            long creditUsed,                // 기관 누적 사용 (credit_transactions 합)
            long creditRemaining,           // allocated − used (음수면 0으로 절삭하지 않고 그대로 — 초과 사용 노출)
            List<MonthlyUsage> monthlyUsage // 최근 6개월 (빈 달은 0)
    ) {}

    public record MonthlyUsage(String month, long credits) {}   // month = "YYYY-MM" (KST)

    // usageSince = 사용량 집계 시작일(계약 시작일, null=전체 누적)
    public record Accounts(LocalDate usageSince, List<Account> items) {}

    public record Account(
            String loginId,
            String alias,
            String status,        // ACTIVE | INACTIVE(잠김)
            String role,
            Instant lastLoginAt,
            long usedCredits,     // 계약 시작일 이후 누적 사용 크레딧
            boolean self,         // 본인(기관 관리자) 여부 — 본인은 제어 불가
            String email,         // 연락 메일(계정 상세). 없으면 null
            Long creditLimit,     // 한 달 크레딧 한도 — null = 제한 없음, 0 = 크레딧 없음(읽기만)
            long monthUsedCredits,// 이번 달(KST) 쓴 크레딧 — 한도와 견주는 값(계정 상세 「이 달 쓴 양」)
            LocalDateTime createdAt // 만든 날
    ) {}

    /**
     * 계정 상세 「저장」 — 창의 세 칸을 통째로 덮어쓴다.
     *
     * @param creditLimit 한 달 크레딧 한도. null = 제한 없음(기관 할당 공유), 0 = 크레딧 없음(읽기만)
     */
    public record UpdateAccount(
            @Size(max = 50) String alias,
            @Email @Size(max = 100) String email,
            @PositiveOrZero Long creditLimit
    ) {}

    public record UpdateAlias(@Size(max = 50) String alias) {}   // null·빈 문자열 = 별칭 제거

    public record UpdateLock(@NotNull Boolean locked) {}

    public record LockResult(String loginId, String status, int canceledJobs) {}

    public record AccountJobs(
            String loginId,
            String alias,
            LocalDate from,
            LocalDate to,
            List<AccountJob> items,
            long totalPages,
            long totalCredits
    ) {}

    public record AccountJob(
            String jobId,
            String fileName,
            String mode,
            String status,          // PENDING | IN_PROGRESS | COMPLETED | FAILED
            int totalPages,
            Integer donePages,      // 변환 중일 때만 (Redis, 장애 시 null)
            Integer failedPages,    // 실패한 쪽 수 (terminal일 때만, 부분 실패 표기용)
            Long credits,           // 소모 크레딧 — 진행 중이면 null (끝나야 확정)
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {}

    /**
     * 크레딧 추가 요청 창의 예상 금액. {@code amountKrw}는 부가세 포함이다.
     *
     * @param unitPriceKrw 크레딧 한 개 단가(부가세 포함) — 아직 임시값이다(설정 billing.credit-price-krw)
     */
    public record CreditEstimate(long credits, long unitPriceKrw, long amountKrw) {}
}
