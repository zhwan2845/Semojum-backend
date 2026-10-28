package com.semojum.backend.domain.auth.entity;

import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.org.entity.Organization;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.user.dto.OutputOptions;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    // 발급형 계정 로그인 ID (운영자가 발급, 1인 1계정)
    @Column(name = "login_id", unique = true, nullable = false)
    private String loginId;

    // 소속 기관 (발급 계정은 필수, 레거시 행은 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column
    private String password;

    // 계정 상태 — INACTIVE면 로그인·토큰 재발급 차단 (운영자만 변경)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    // 계정 역할 — ROLE_ADMIN은 운영·테스트용 계정 분류 (운영자만 변경)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // ROLE_ADMIN 사용처 구분 — ROLE_ADMIN 외 계정에선 의미 없음
    // - WEB: 운영자 콘솔 전용(Origin이 콘솔 주소일 때만 로그인, 앱 불가)
    // - APP: 에디터 앱용(관리자 계정으로 에디터를 쓴다)
    // - null: 스코프 미지정 — 콘솔 로그인만 막힌다
    @Column(name = "admin_scope", length = 10)
    private String adminScope;

    public static final String ADMIN_SCOPE_WEB = "WEB";
    public static final String ADMIN_SCOPE_APP = "APP";

    // 별칭 — 기관 관리자가 "누가 쓰는 계정인지"를 역할명으로 적는다 (실명 대신 "수학 담당" 권장)
    @Column(length = 50)
    private String alias;

    // 마지막 로그인 시각 (운영자 기관·계정 표 · 기관 관리자 소속 계정 표) — 로그인 성공 시 갱신
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // 삭제 표식 — 실삭제하지 않는다(보관 기간 정책 미정). 삭제 시 INACTIVE로도 전환돼 로그인 차단
    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 계정별 기본 점역 옵션 — 설정 › 기본 점역 옵션 설정 창에서 사용자가 직접 고친다.
    // null = 한 번도 저장한 적 없음 → 조회 시 공장 기본값을 내려준다(resolveBrailleOptions)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "braille_options", columnDefinition = "jsonb")
    private BrailleOptions brailleOptions;

    // 출력용 점자문서 설정의 계정 기본값. null = 저장한 적 없음 → resolveOutputOptions가 공장 기본값
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_options", columnDefinition = "jsonb")
    private OutputOptions outputOptions;

    // 기본 점역 옵션 설정 창에만 있는 계정 기본값 둘 — 점역 양식 드롭다운 · 페이지행 면. null이면 공장 기본값
    @Column(name = "default_form_id", length = 64)
    private String defaultFormId;

    @Column(name = "default_page_number_line", length = 8)
    private String defaultPageNumberLine;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public User(String loginId, Organization organization, String password) {
        this.loginId = loginId;
        this.organization = organization;
        this.password = password;
        this.status = UserStatus.ACTIVE;
        this.role = Role.ROLE_USER;
        this.createdAt = LocalDateTime.now();
    }

    // 운영자 비밀번호 재발급 (사용자 스스로 변경은 불가 정책)
    public void reissuePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public void changeAlias(String alias) {
        this.alias = alias;
    }

    public void touchLastLogin() {
        this.lastLoginAt = Instant.now();
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.status = UserStatus.INACTIVE;   // 기존 차단 경로(로그인·refresh AUTH4004) 재사용
    }

    /** 기본 점역 옵션 설정 「확인」 — 창이 항목 전부를 보여주므로 통째로 덮어쓴다(부분 갱신이 아니다) */
    public void updateBrailleOptions(BrailleOptions options) {
        this.brailleOptions = options;
    }

    /** 기본 점역 옵션 설정 「확인」의 나머지 둘 — 점역 양식과 페이지행 면 */
    public void updateDefaults(String formId, String pageNumberLine) {
        this.defaultFormId = formId;
        this.defaultPageNumberLine = pageNumberLine;
    }

    /** 저장한 적 없는 계정도 완전한 형태를 받는다 — 읽는 쪽에 null 분기를 안 남긴다 */
    public BrailleOptions resolveBrailleOptions() {
        return brailleOptions == null
                ? BrailleOptions.defaults()
                : brailleOptions.withDefaults();
    }

    /** 출력용 점자문서 설정 「설정 저장」 — 통째로 덮어쓴다 */
    public void updateOutputOptions(OutputOptions options) {
        this.outputOptions = options;
    }

    /** 저장한 적 없는 계정도 완전한 형태를 받는다 */
    public OutputOptions resolveOutputOptions() {
        return outputOptions == null
                ? OutputOptions.defaults()
                : outputOptions.withDefaults();
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }
}