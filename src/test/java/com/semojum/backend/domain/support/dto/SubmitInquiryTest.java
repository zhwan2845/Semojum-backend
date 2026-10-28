package com.semojum.backend.domain.support.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 홈페이지 문의 접수 본문 — 컨트롤러 안에서 SupportDto로 옮긴 뒤에도 JSON 키 · 검증 규칙이 그대로인지 */
class SubmitInquiryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 공개_문의_본문의_키를_그대로_읽는다() throws Exception {
        SupportDto.SubmitInquiry body = mapper.readValue("""
                {"type":"ONBOARDING","name":"홍길동","email":"a@b.com","message":"도입 문의","website":"bot"}
                """, SupportDto.SubmitInquiry.class);

        assertEquals("ONBOARDING", body.type());
        assertEquals("홍길동", body.name());
        assertEquals("a@b.com", body.email());
        assertEquals("도입 문의", body.message());
        assertEquals("bot", body.website());
        assertTrue(validator.validate(body).isEmpty());
    }

    @Test
    void 필수값_이메일_형식_길이를_검증한다() {
        SupportDto.SubmitInquiry bad = new SupportDto.SubmitInquiry(
                " ", "가".repeat(51), "not-an-email", "가".repeat(2001), null);

        Set<String> fields = validator.validate(bad).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
        assertEquals(Set.of("type", "name", "email", "message"), fields);
    }

    @Test
    void 이름과_허니팟은_선택이다() {
        assertTrue(validator.validate(new SupportDto.SubmitInquiry("ETC", null, "a@b.com", "내용", null)).isEmpty());
    }
}
