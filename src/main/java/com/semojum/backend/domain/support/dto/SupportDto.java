package com.semojum.backend.domain.support.dto;

import com.semojum.backend.domain.support.entity.RequestDetails;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 문의·공지·주문 (운영자 작성 · 기관 관리자 조회/요청) */
public class SupportDto {

    /**
     * 문의 접수 (무인증 공개 — PublicInquiryController). 홈페이지는 JSON, 앱 문의하기 창은 첨부 때문에 multipart로 보낸다.
     *
     * <p>{@code type}: 앱 분류 {@code ACCOUNT · RESULT · BILLING · USAGE}(QuestionCategory) 또는
     * 홈페이지의 {@code ONBOARDING(도입 문의) · ERROR_REPORT · ETC}.
     */
    public record SubmitInquiry(
            @NotBlank String type,
            @Size(max = 50) String name,                        // 보낸 사람 이름 (선택 — 홈페이지)
            @NotBlank @Email @Size(max = 100) String email,
            @Size(max = 200) String title,                      // 제목 (선택 — 앱 문의하기 창)
            @Size(max = 100) String orgName,                    // 기관명 (선택 — 앱 문의하기 창)
            @NotBlank @Size(max = 2000) String message,
            String website                                      // 허니팟 — 화면에서 숨김, 채워지면 봇
    ) {}

    // ── 공지 ──
    public record CreateNotice(
            UUID targetOrganizationId,               // null = 전체 공지
            @NotBlank @Size(max = 200) String title,
            @NotBlank String body,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            String category                          // MAINTENANCE | RELEASE | MATERIAL | null(분류 없음 · 수정은 그대로)
    ) {
        public CreateNotice(UUID targetOrganizationId, String title, String body, LocalDate startsOn, LocalDate endsOn) {
            this(targetOrganizationId, title, body, startsOn, endsOn, null);
        }
    }

    public record NoticeItem(
            UUID id,
            UUID targetOrganizationId,
            String targetOrgName,      // null = 전체
            String title,
            String body,
            LocalDate startsOn,
            LocalDate endsOn,
            String displayStatus,      // SCHEDULED(예정) | ACTIVE(노출 중) | ENDED(종료)
            Instant createdAt,
            boolean pinned,            // 게시판 맨 위 고정. 운영자 콘솔 「고정 · 고정 해제」
            String category            // MAINTENANCE(점검) · RELEASE(새 판) · MATERIAL(자료) · null
    ) {}

    /** 게시판 고정 · 고정 해제 */
    public record UpdatePinned(Boolean pinned) {}

    // 로그인 전 공개 공지 (무인증) — 전체 대상 공지만, 기관 정보 미포함
    /** @param category 공지사항 창의 탭 — MAINTENANCE(점검) · RELEASE(새 판) · MATERIAL(자료) · null(분류 없음) */
    public record PublicNotice(
            UUID id, String title, String body,
            LocalDate startsOn, LocalDate endsOn, Instant createdAt, String category
    ) {}

    public record OrgNotice(
            UUID id,
            String scope,              // ALL(전체) | ORG(우리 기관)
            String title,
            String body,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant createdAt,
            String category            // MAINTENANCE · RELEASE · MATERIAL · null
    ) {}

    // ── 문의 ──
    // 목록 행 — 본문 전문 대신 preview 100자 + 첨부 개수만.
    // 본문·인라인 이미지는 상세 GET /api/admin/inquiries/{inquiryId}
    public record InquirySummary(
            UUID id,
            String type,               // EMAIL이면 보낸 사람 자리에 senderEmail 표시
            String status,
            String orgName,            // 미가입·메일 문의는 null
            String loginId,
            String senderEmail,        // 메일 · 홈페이지 · 앱 문의하기의 답 받을 주소
            String subject,            // 메일 제목 · 문의하기 제목 (그 외 null)
            String preview,            // 본문 앞 100자
            Instant createdAt,
            Instant statusChangedAt,
            int attachmentCount,       // 첨부+인라인 이미지 개수
            String senderName,         // 문의하기의 보낸 사람 이름 (선택 입력)
            String senderOrgName       // 문의하기의 기관명 (선택 입력 — 가입 기관이 아니어도 적는다)
    ) {}

    // 문의 상세 — 본문 전문 + 인라인 이미지(presigned URL 15분, 바로 렌더) + 파일 첨부(url 포함)
    public record InquiryDetail(
            UUID id,
            String type,
            String status,
            String orgName,
            String loginId,
            String senderEmail,
            String subject,
            String message,
            Instant createdAt,
            Instant statusChangedAt,
            List<InlineImage> inlineImages,           // 메일 본문 인라인 이미지 — url로 즉시 표시
            List<InquiryAttachmentItem> attachments,  // 파일 첨부 — 각 url(presigned)로 바로 받는다
            String senderName,
            String senderOrgName,
            RequestDetails requestDetails             // 기관 요청 창의 칸(계정 발급 · 크레딧 추가). 그 밖은 null
    ) {}

    public record InlineImage(UUID id, String fileName, String contentType, long sizeBytes, String url) {}

    // 파일 첨부 — url은 presigned 15분(만료 시 상세 재조회로 재발급). 목록에는 안 실림(상세 전용)
    public record InquiryAttachmentItem(UUID id, String fileName, String contentType, long sizeBytes, String url) {}

    // 운영자 문의 목록 페이지 응답
    public record InquiryPage(
            List<InquirySummary> items,
            int page, int size,
            long totalElements, int totalPages
    ) {}

    public record UpdateInquiryStatus(@NotBlank String status) {}   // OPEN | IN_REVIEW | ANSWERED

    /**
     * 기관 요청 접수. 창의 칸은 모두 선택이다 — 칸을 모르는 구 앱은 {@code message}만 보낸다.
     * 계정 발급(ACCOUNT_ISSUE): accountAlias · recipientEmail · creditScope(SHARED | NONE).
     * 크레딧 추가(CREDIT_ADD): creditAmount · invoiceType(ORG | PERSONAL | NONE).
     */
    public record CreateRequest(
            @NotBlank String type,                    // CREDIT_ADD | ACCOUNT_ISSUE
            @Size(max = 1000) String message,
            @Size(max = 50) String accountAlias,
            @Email @Size(max = 100) String recipientEmail,
            String creditScope,
            @Positive Long creditAmount,
            String invoiceType
    ) {
        /** 칸 없이 메시지만 — 구 앱과 같은 요청 */
        public CreateRequest(String type, String message) {
            this(type, message, null, null, null, null, null);
        }
    }

    /** @param details 창의 칸 — 칸이 생기기 전 요청은 null */
    public record RequestItem(
            UUID id,
            String type,
            String status,
            String message,
            Instant createdAt,
            RequestDetails details
    ) {}

    // ── 주문·수납 ──
    /**
     * 주문 기록. {@code amountKrw}는 <b>부가세 포함</b> 금액이다(주문 상세 「₩780,000 (부가세 포함)」).
     *
     * @param requestId 이 주문을 만든 기관 요청(크레딧 추가) — 주문 상세의 「요청한 사람 · 요청한 날」. 선택
     */
    public record CreateOrder(
            @NotNull UUID organizationId,
            @NotNull LocalDate orderDate,
            @NotBlank @Size(max = 200) String description,
            @NotNull @PositiveOrZero Long amountKrw,
            @PositiveOrZero Long creditAmount,
            UUID requestId
    ) {
        public CreateOrder(UUID organizationId, LocalDate orderDate, String description, Long amountKrw, Long creditAmount) {
            this(organizationId, orderDate, description, amountKrw, creditAmount, null);
        }
    }

    public record UpdateOrder(
            LocalDate paidAt,          // 입금 확인 기록 (null이면 변경 없음)
            String invoiceStatus       // PENDING | ISSUED (null이면 변경 없음)
    ) {}

    public record OrderItem(
            UUID id,
            UUID organizationId,
            String orgName,
            LocalDate orderDate,
            String description,
            long amountKrw,
            Long creditAmount,
            LocalDate paidAt,          // null = 미납
            String invoiceStatus,
            String receiptFileName,    // 증빙 파일명 (null = 미첨부)
            Instant createdAt,
            UUID requestId,            // 이 주문을 만든 기관 요청 (null = 요청 없이 기록)
            String quoteFileName       // 견적서 파일명 (null = 미첨부)
    ) {}

    public record OrgOrders(String receiptEmail, List<OrgOrderItem> items) {}

    /**
     * 기관 관리자 주문 내역 · 주문 상세. {@code amountKrw}는 부가세 포함 금액이다.
     *
     * @param requestedBy     요청한 사람 loginId — 요청 없이 기록한 주문은 null(요청 · 요청자가 지워졌어도 null)
     * @param requestedByRole 요청한 사람 역할 — {@code ROLE_ORG_ADMIN}(화면 「관리자」) · {@code ROLE_USER}
     * @param requestedAt     요청한 날(요청 접수 시각)
     * @param quoteFileName   견적서 파일명 (null = 미첨부 → 「견적서 내려받기」 숨김)
     */
    public record OrgOrderItem(
            UUID id,
            LocalDate orderDate,
            String description,
            long amountKrw,
            Long creditAmount,
            LocalDate paidAt,
            String invoiceStatus,
            String receiptFileName,    // 증빙 파일명 (null = 미첨부 → 내려받기 버튼 숨김)
            String requestedBy,
            String requestedByRole,
            Instant requestedAt,
            String quoteFileName
    ) {}

    // 증빙 내려받기 — presigned URL(15분). FE는 즉시 열거나 저장 (URL 장기 캐시 금지)
    public record ReceiptDownload(String fileName, String url) {}

    public record UpdateReceiptEmail(@Email @Size(max = 100) String email) {}   // null = 제거
}
