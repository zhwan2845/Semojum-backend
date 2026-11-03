package com.semojum.backend.domain.support.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * 공지 — 운영자가 작성, 기관 관리자 화면과 모든 계정의 게시판 맨 위에 노출.
 * targetOrganizationId null = 전체 공지. 노출 기간이 지나면 자동 종료(조회 시 기간 필터).
 */
@Entity
@Table(name = "notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "target_organization_id", columnDefinition = "uuid")
    private UUID targetOrganizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    public static final Set<String> CATEGORIES = Set.of("MAINTENANCE", "RELEASE", "MATERIAL");

    // 분류 — MAINTENANCE(점검) · RELEASE(새 판) · MATERIAL(자료). null = 분류 없음(「전체」 탭에만)
    @Column(length = 20)
    private String category;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // 게시판 목록의 조회수 — 게시판에서 공지를 열 때마다 +1
    @Column(name = "view_count", nullable = false, insertable = false)
    private int viewCount;

    // 게시판 맨 위 고정 — 운영자 콘솔 「고정 해제」면 false, 게시판에서 내려간다. 기관 관리자 화면과는 무관
    @Column(nullable = false, insertable = false)
    private boolean pinned = true;

    @Builder
    public Notice(UUID targetOrganizationId, String title, String body, String category,
                  LocalDate startsOn, LocalDate endsOn) {
        this.targetOrganizationId = targetOrganizationId;
        this.title = title;
        this.body = body;
        this.category = category;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    /**
     * 운영자 공지 수정 — 등록 창과 같은 항목을 통째로 덮어쓴다.
     * 분류만은 null이면 그대로 둔다 — 분류 칸이 없는 콘솔이 고쳐도 분류가 지워지지 않게
     */
    public void update(UUID targetOrganizationId, String title, String body, String category,
                       LocalDate startsOn, LocalDate endsOn) {
        this.targetOrganizationId = targetOrganizationId;
        this.title = title;
        this.body = body;
        if (category != null) this.category = category;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    public void updatePinned(boolean pinned) {
        this.pinned = pinned;
    }

    public void increaseViewCount() {
        this.viewCount++;
    }
}
