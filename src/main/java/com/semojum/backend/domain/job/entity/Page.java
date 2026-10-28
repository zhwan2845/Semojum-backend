package com.semojum.backend.domain.job.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pages", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"job_id", "page_no"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(nullable = false)
    private int pageNo;

    @Column(nullable = false)
    private String pdfPath;

    // 원본 미리보기용 렌더 이미지(JPEG) 경로 — FE 좌측 패널이 pdf.js 대신 <img>로 바로 그린다.
    // null = 아직 생성 전이거나 렌더 실패 → 조회 API가 원본 PDF로 폴백한다
    @Column
    private String imagePath;

    @Column(nullable = false)
    private String status;

    // 차단(BLOCKED) 사유 — CREDIT_EXHAUSTED · CANCELED. null = 사유 없음(AI 오류 · 시간 초과 · 재시도 초과)
    @Column(name = "blocked_reason", length = 40)
    private String blockedReason;

    /** 기관 잔여 크레딧이 0 이하라 AI에 보내지 않고 막은 쪽 */
    public static final String BLOCKED_CREDIT_EXHAUSTED = "CREDIT_EXHAUSTED";
    /** 변환 취소로 막은 쪽 — 사용자가 멈춘 쪽이라 「실패한 쪽 다시 변환」 대상이 아니다 */
    public static final String BLOCKED_CANCELED = "CANCELED";

    /** 처리가 끝난 쪽(성공 · 실패) — 진행률의 분자 · 작업 종료 판정 */
    public static final List<String> TERMINAL_STATUSES = List.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED");
    /** 결과가 나온 쪽 — 크레딧 차감 · 성공 쪽 수 */
    public static final List<String> SUCCESS_STATUSES = List.of("COMPLETED", "NEEDS_REVIEW");

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Page(Job job, int pageNo, String pdfPath) {
        this.job = job;
        this.pageNo = pageNo;
        this.pdfPath = pdfPath;
        this.status = "PENDING";
        this.createdAt = LocalDateTime.now();
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    public void block(String reason) {
        this.status = "BLOCKED";
        this.blockedReason = reason;
    }

    /** 이어서 처리 · 다시 변환 — 막혔던 쪽을 다시 대기열로 돌린다(사유도 지운다) */
    public void requeue() {
        this.status = "PENDING";
        this.blockedReason = null;
    }

    public void updateImagePath(String imagePath) {
        this.imagePath = imagePath;
    }
}