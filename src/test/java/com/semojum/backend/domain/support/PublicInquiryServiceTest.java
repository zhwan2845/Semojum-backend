package com.semojum.backend.domain.support;

import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.repository.InquiryAttachmentRepository;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.global.s3.S3Service;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.UUID;
import com.semojum.backend.domain.support.service.PublicInquiryService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

// 공개 문의 접수의 남용 방어 규칙 (저장소·Redis mock)
class PublicInquiryServiceTest {

    private InquiryRepository inquiryRepository;
    private InquiryAttachmentRepository attachmentRepository;
    private S3Service s3Service;
    private ValueOperations<String, String> valueOps;
    private PublicInquiryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        inquiryRepository = Mockito.mock(InquiryRepository.class);
        RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        attachmentRepository = Mockito.mock(InquiryAttachmentRepository.class);
        s3Service = Mockito.mock(S3Service.class);
        service = new PublicInquiryService(inquiryRepository, attachmentRepository, s3Service, redisTemplate);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(inquiryRepository.save(any())).thenAnswer(inv -> {
            Inquiry i = inv.getArgument(0);
            ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
            return i;
        });
    }

    private static SupportDto.SubmitInquiry req(String type, String name, String email, String message, String website) {
        return new SupportDto.SubmitInquiry(type, name, email, null, null, message, website);
    }

    private void submit(String type, String name, String email, String message, String website, String ip) {
        service.submit(req(type, name, email, message, website), null, ip);
    }

    @Test
    void 정상_접수() {
        submit("ONBOARDING", "홍길동", "hong@example.com", "도입 문의드립니다", null, "1.2.3.4");
        verify(inquiryRepository).save(any());
    }

    @Test
    void 허니팟이_채워지면_성공한_척_버림() {
        submit("ONBOARDING", null, "bot@spam.com", "spam", "http://spam.link", "1.2.3.4");
        verify(inquiryRepository, never()).save(any());   // 예외 없이 조용히 폐기
    }

    @Test
    void 허용_외_유형_거절() {
        CustomException e = assertThrows(CustomException.class, () ->
                submit("CREDIT_ADD", null, "a@b.com", "msg", null, "1.2.3.4"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 시간당_5건_초과는_거절() {
        when(valueOps.increment(anyString())).thenReturn(6L);
        CustomException e = assertThrows(CustomException.class, () ->
                submit("ETC", null, "a@b.com", "msg", null, "1.2.3.4"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    void 레이트리밋_확인_실패는_접수_허용() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        submit("ETC", null, "a@b.com", "msg", null, "1.2.3.4");
        verify(inquiryRepository).save(any());
    }

    // ── 앱 문의하기 창 ─────────────────────────────────────────────

    /** 피그마 문의하기 분류 4종을 그대로 받는다 */
    @Test
    void 앱_분류_네_가지를_받는다() {
        for (String type : List.of("ACCOUNT", "RESULT", "BILLING", "USAGE")) {
            submit(type, null, "a@b.com", "msg", null, "1.2.3.4");
        }
        verify(inquiryRepository, times(4)).save(any());
    }

    /** 제목은 subject(운영자 목록의 제목 자리), 이름 · 기관명은 따로 둔다 */
    @Test
    void 제목_이름_기관명을_각자_자리에_둔다() {
        service.submit(new SupportDto.SubmitInquiry("ACCOUNT", "홍길동", "a@b.com", " 계정 두 개 받을 수 있을까요 ",
                "한국점자도서관", "본문", null), null, "1.2.3.4");
        verify(inquiryRepository).save(argThat(i -> "계정 두 개 받을 수 있을까요".equals(i.getSubject())
                && "홍길동".equals(i.getSenderName()) && "한국점자도서관".equals(i.getSenderOrgName())));
    }

    @Test
    void 첨부는_S3에_올리고_문의에_붙인다() throws Exception {
        MockMultipartFile f = new MockMultipartFile("files", "작업정보.txt", "text/plain", new byte[10]);
        service.submit(req("RESULT", null, "a@b.com", "msg", null), List.of(f), "1.2.3.4");
        verify(s3Service).uploadFile(org.mockito.ArgumentMatchers.startsWith("inquiries/"), any(), eq("text/plain"));
        verify(attachmentRepository).save(argThat(a -> "작업정보.txt".equals(a.getFileName()) && !a.isInline()));
    }

    @Test
    void 첨부는_세_개까지다() {
        List<org.springframework.web.multipart.MultipartFile> four = List.of(
                file("a.png", 1), file("b.png", 1), file("c.png", 1), file("d.png", 1));
        CustomException e = assertThrows(CustomException.class,
                () -> service.submit(req("RESULT", null, "a@b.com", "msg", null), four, "1.2.3.4"));
        assertEquals(ErrorCode.INQUIRY_ATTACHMENT_INVALID, e.getErrorCode());
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    void 첨부_한_개가_10MB를_넘으면_413이다() {
        CustomException e = assertThrows(CustomException.class, () -> service.submit(
                req("RESULT", null, "a@b.com", "msg", null), List.of(file("big.pdf", 10 * 1024 * 1024 + 1)), "1.2.3.4"));
        assertEquals(ErrorCode.INQUIRY_ATTACHMENT_TOO_LARGE, e.getErrorCode());
    }

    @Test
    void 실행_파일은_받지_않는다() {
        CustomException e = assertThrows(CustomException.class, () -> service.submit(
                req("RESULT", null, "a@b.com", "msg", null), List.of(file("setup.exe", 1)), "1.2.3.4"));
        assertEquals(ErrorCode.INQUIRY_ATTACHMENT_INVALID, e.getErrorCode());
    }

    private static MockMultipartFile file(String name, int bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", new byte[bytes]);
    }
}
