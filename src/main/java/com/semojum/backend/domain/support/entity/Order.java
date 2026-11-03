package com.semojum.backend.domain.support.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 주문·수납 — 계약·입금은 기관과 직접 주고받고, 확인되면 운영자가 기록한다(결제 연동 없음).
 * paidAt null = 미납. creditAmount는 참고값 — 할당 크레딧 반영은 운영자가 별도로 한다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    public static final String INVOICE_PENDING = "PENDING";  // 발행 대기
    public static final String INVOICE_ISSUED = "ISSUED";    // 발행 완료

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid")
    private UUID organizationId;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "amount_krw", nullable = false)
    private long amountKrw;

    @Column(name = "credit_amount")
    private Long creditAmount;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "invoice_status", nullable = false, length = 20)
    private String invoiceStatus;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // 증빙(계산서·전표) 파일 — 운영자 업로드, S3 receipts/{orderId}/ (presigned GET 전용). 재업로드 = 교체
    @Column(name = "receipt_file_key", length = 300)
    private String receiptFileKey;

    @Column(name = "receipt_file_name", length = 200)
    private String receiptFileName;

    @Column(name = "receipt_uploaded_at")
    private Instant receiptUploadedAt;

    // 이 주문을 만든 기관 요청(크레딧 추가) — 주문 상세 「요청한 사람 · 요청한 날」. 요청 없이 만든 주문은 null
    @Column(name = "request_id", columnDefinition = "uuid")
    private UUID requestId;

    // 견적서 — 운영자가 올린다(재업로드 = 교체). 주문 상세 「견적서 내려받기」
    @Column(name = "quote_file_key", length = 300)
    private String quoteFileKey;

    @Column(name = "quote_file_name", length = 200)
    private String quoteFileName;

    @Column(name = "quote_uploaded_at")
    private Instant quoteUploadedAt;

    @Builder
    public Order(UUID organizationId, LocalDate orderDate, String description,
                 long amountKrw, Long creditAmount, UUID requestId) {
        this.organizationId = organizationId;
        this.requestId = requestId;
        this.orderDate = orderDate;
        this.description = description;
        this.amountKrw = amountKrw;
        this.creditAmount = creditAmount;
        this.invoiceStatus = INVOICE_PENDING;
    }

    public void recordPayment(LocalDate paidAt) {
        this.paidAt = paidAt;
    }

    public void changeInvoiceStatus(String invoiceStatus) {
        this.invoiceStatus = invoiceStatus;
    }

    public void attachReceipt(String fileKey, String fileName) {
        this.receiptFileKey = fileKey;
        this.receiptFileName = fileName;
        this.receiptUploadedAt = Instant.now();
    }

    public void attachQuote(String fileKey, String fileName) {
        this.quoteFileKey = fileKey;
        this.quoteFileName = fileName;
        this.quoteUploadedAt = Instant.now();
    }
}
