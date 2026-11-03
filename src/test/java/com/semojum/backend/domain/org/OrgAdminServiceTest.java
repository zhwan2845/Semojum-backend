package com.semojum.backend.domain.org;

import com.semojum.backend.domain.org.dto.OrgDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobCancelService;
import com.semojum.backend.domain.job.service.JobProgressReader;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.service.OrgAdminService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// 기관 관리의 권한·잠금 규칙 검증 (저장소는 전부 mock)
class OrgAdminServiceTest {

    private UserRepository userRepository;
    private UserSessionRepository userSessionRepository;
    private JobRepository jobRepository;
    private JobCancelService jobCancelService;
    private OrgAdminService service;

    private Organization orgA;
    private Organization orgB;
    private User admin;      // orgA의 ROLE_ORG_ADMIN
    private User member;     // orgA의 점역사
    private User outsider;   // orgB의 점역사

    private static void setId(Object entity, String field, Object value) throws Exception {
        var f = entity.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(entity, value);
    }

    private User user(String loginId, Organization org, Role role) throws Exception {
        User u = User.builder().loginId(loginId).organization(org).password("pw").build();
        u.changeRole(role);
        setId(u, "id", UUID.randomUUID());
        return u;
    }

    com.semojum.backend.domain.billing.service.CreditBalanceService balanceService;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = Mockito.mock(UserRepository.class);
        userSessionRepository = Mockito.mock(UserSessionRepository.class);
        jobRepository = Mockito.mock(JobRepository.class);
        jobCancelService = Mockito.mock(JobCancelService.class);
        service = new OrgAdminService(userRepository, userSessionRepository,
                Mockito.mock(CreditTransactionRepository.class), jobRepository,
                jobCancelService, Mockito.mock(JobProgressReader.class),
                balanceService = Mockito.mock(com.semojum.backend.domain.billing.service.CreditBalanceService.class));

        orgA = Organization.builder().name("한국점자도서관").code("kblib").build();
        setId(orgA, "id", UUID.randomUUID());
        orgB = Organization.builder().name("서울맹학교").code("snsb").build();
        setId(orgB, "id", UUID.randomUUID());

        admin = user("kblib01", orgA, Role.ROLE_ORG_ADMIN);
        member = user("kblib02", orgA, Role.ROLE_USER);
        outsider = user("snsb01", orgB, Role.ROLE_USER);

        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(userRepository.findByLoginId("kblib02")).thenReturn(Optional.of(member));
        when(userRepository.findByLoginId("snsb01")).thenReturn(Optional.of(outsider));
        when(userRepository.findByLoginId("kblib01")).thenReturn(Optional.of(admin));
    }

    @Test
    void 일반_점역사는_기관_관리_접근_불가() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.getAccounts(member.getId().toString()));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 타_기관_계정은_제어_불가() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.updateAlias(admin.getId().toString(), "snsb01", "국어 담당"));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 본인_잠금은_거절() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.updateLock(admin.getId().toString(), "kblib01", true));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 잠금은_세션_끊고_진행_중_변환도_취소() {
        Job running = Mockito.mock(Job.class);
        when(running.getId()).thenReturn("job_x");
        when(jobRepository.findByUserIdAndStatusInOrderByStartedAtDesc(eq(member.getId()), anyList()))
                .thenReturn(List.of(running));

        var result = service.updateLock(admin.getId().toString(), "kblib02", true);

        assertEquals(UserStatus.INACTIVE, member.getStatus());
        verify(userSessionRepository).revokeAllActiveByUser(eq(member), any());
        verify(jobCancelService).cancel(member.getId().toString(), "job_x");
        assertEquals(1, result.canceledJobs());
        assertEquals("INACTIVE", result.status());
    }

    @Test
    void 잠금_해제는_ACTIVE_복귀_취소_없음() {
        member.changeStatus(UserStatus.INACTIVE);
        var result = service.updateLock(admin.getId().toString(), "kblib02", false);
        assertEquals(UserStatus.ACTIVE, member.getStatus());
        assertEquals(0, result.canceledJobs());
        verify(jobCancelService, never()).cancel(any(), any());
    }

    @Test
    void 별칭_빈_문자열은_제거로_처리() {
        service.updateAlias(admin.getId().toString(), "kblib02", "  ");
        assertNull(member.getAlias());
        service.updateAlias(admin.getId().toString(), "kblib02", " 수학 담당 ");
        assertEquals("수학 담당", member.getAlias());
    }

    /** 크레딧 추가 요청 창 — 3,000크레딧이면 피그마 예시 ₩780,000(임시 단가 260원, 부가세 포함) */
    @Test
    void 예상_금액은_양_곱하기_단가다() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "creditPriceKrw", 260L);
        OrgDto.CreditEstimate e = service.getCreditEstimate(admin.getId().toString(), 3000);
        assertEquals(780_000L, e.amountKrw());
        assertEquals(260L, e.unitPriceKrw());

        CustomException bad = assertThrows(CustomException.class, () -> service.getCreditEstimate(admin.getId().toString(), 0));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, bad.getErrorCode());
        CustomException member403 = assertThrows(CustomException.class, () -> service.getCreditEstimate(member.getId().toString(), 10));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, member403.getErrorCode());
    }

    /** 계정 상세 「저장」 — 별칭 · 메일 · 한도를 덮어쓰고, 관문이 기억한 계정 잔여를 버린다 */
    @Test
    void 계정_상세를_저장하면_한도가_바로_걸린다() {
        service.updateAccount(admin.getId().toString(), "kblib02",
                new OrgDto.UpdateAccount(" 수학 담당 ", "math@kblib.or.kr", 2000L));
        assertEquals("수학 담당", member.getAlias());
        assertEquals("math@kblib.or.kr", member.getEmail());
        assertEquals(2000L, member.getCreditLimit());
        verify(balanceService).evictAccount(member.getId());

        service.updateAccount(admin.getId().toString(), "kblib02", new OrgDto.UpdateAccount(null, null, null));
        assertNull(member.getCreditLimit(), "null = 제한 없음");
    }

    @Test
    void 다른_기관_계정은_저장할_수_없다() {
        CustomException e = assertThrows(CustomException.class, () -> service.updateAccount(admin.getId().toString(),
                "snsb01", new OrgDto.UpdateAccount(null, null, 0L)));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }
}
