package com.semojum.backend.domain.support.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 문의 — 기관 관리자의 요청(크레딧 추가·계정 발급) · 오류 신고 · 홈페이지 문의 · 메일이 운영자 문의 목록으로 모인다.
 * 상태: OPEN(미답변) → IN_REVIEW(확인 중) → ANSWERED(답변 완료). 취소는 OPEN에서 hard delete.
 * 홈페이지 · 메일 문의는 organizationId/userId가 null이다.
 */
@Entity
@Table(name = "inquiries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry {

    public static final String TYPE_CREDIT_ADD = "CREDIT_ADD";
    public static final String TYPE_ACCOUNT_ISSUE = "ACCOUNT_ISSUE";
    public static final String TYPE_ERROR_REPORT = "ERROR_REPORT";
    public static final String TYPE_ONBOARDING = "ONBOARDING";
    public static final String TYPE_EMAIL = "EMAIL";     // 회사 메일함 수신 (MailInboxPoller)
    public static final String TYPE_ETC = "ETC";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_IN_REVIEW = "IN_REVIEW";
    public static final String STATUS_ANSWERED = "ANSWERED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "organization_id", columnDefinition = "uuid")
    private UUID organizationId;

    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(columnDefinition = "text")
    private String message;

    // 메일 문의(TYPE_EMAIL) 전용 — 보낸 사람 자리에 메일 주소를 표시한다
    @Column(name = "sender_email", length = 100)
    private String senderEmail;

    // 메일 제목 · 문의하기 창의 제목. 2026-11 전에 받은 홈페이지 문의는 보낸 사람 이름이 들어 있다
    @Column(length = 300)
    private String subject;

    // 홈페이지 · 앱 문의하기의 보낸 사람 이름 · 기관명(둘 다 선택 입력)
    @Column(name = "sender_name", length = 50)
    private String senderName;

    @Column(name = "sender_org_name", length = 100)
    private String senderOrgName;

    // 기관 관리자 요청 창의 칸(계정 발급 · 크레딧 추가). 그 밖의 문의는 null
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_details", columnDefinition = "jsonb")
    private RequestDetails requestDetails;

    // "UIDVALIDITY:UID" — 폴링 재수집 시 중복 방지 (DB 유니크)
    @Column(name = "mail_uid", length = 60)
    private String mailUid;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Builder
    public Inquiry(String type, UUID organizationId, UUID userId, String message,
                   String senderEmail, String subject, String senderName, String senderOrgName,
                   RequestDetails requestDetails, String mailUid) {
        this.type = type;
        this.status = STATUS_OPEN;
        this.organizationId = organizationId;
        this.userId = userId;
        this.message = message;
        this.senderEmail = senderEmail;
        this.subject = subject;
        this.senderName = senderName;
        this.senderOrgName = senderOrgName;
        this.requestDetails = requestDetails;
        this.mailUid = mailUid;
    }

    public void changeStatus(String status) {
        this.status = status;
        this.statusChangedAt = Instant.now();
    }
}
