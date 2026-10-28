package com.semojum.backend.domain.user;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.user.dto.OutputOptions;
import com.semojum.backend.domain.user.service.OutputOptionsService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;

/** 출력용 점자문서 설정의 계정 기본값 */
class OutputOptionsServiceTest {

    static final UUID USER_ID = UUID.randomUUID();
    User user;
    OutputOptionsService service;

    @BeforeEach
    void setUp() {
        UserRepository repo = Mockito.mock(UserRepository.class);
        user = User.builder().loginId("testorg01").password("x").build();
        when(repo.findById(USER_ID)).thenReturn(Optional.of(user));
        service = new OutputOptionsService(repo);
    }

    private OutputOptions of(String paper, Integer lines, Integer cells, Integer start, String pageNumber,
                             Boolean duplex, String lineBreak) {
        return new OutputOptions(paper, lines, cells, start, pageNumber, duplex, lineBreak);
    }

    @Test
    void 저장한_적_없으면_공장_기본값이다() {
        OutputOptions o = service.get(USER_ID.toString());

        assertEquals(new OutputOptions("10x11", 26, 32, 1, "odd", true, "syllable"), o);
    }

    @Test
    void 보낸_값을_저장하고_다시_읽으면_그대로다() {
        OutputOptions sent = of("11x11.5", 25, 30, 3, "every", false, "word");

        assertEquals(sent, service.update(USER_ID.toString(), sent));
        assertEquals(sent, service.get(USER_ID.toString()));
    }

    /** 창 전체를 덮어쓴다 — 빠진 항목은 이전 값이 아니라 기본값이다 */
    @Test
    void 통째로_덮어써서_빠진_항목은_기본값이_된다() {
        service.update(USER_ID.toString(), of("11x11.5", 25, 30, 3, "every", false, "word"));

        OutputOptions o = service.update(USER_ID.toString(), of(null, 20, null, null, null, null, null));

        assertEquals(new OutputOptions("10x11", 20, 32, 1, "odd", true, "syllable"), o);
    }

    @Test
    void 본문이_없으면_공장_기본값으로_저장한다() {
        assertEquals(OutputOptions.defaults(), service.update(USER_ID.toString(), null));
    }

    /** 용지 이름은 허용값을 두지 않는다(기획에 규격 목록이 없다) — 비었으면 기본 용지, 앞뒤 공백은 걷는다 */
    @Test
    void 용지_이름은_그대로_보관하고_비었으면_기본_용지다() {
        assertEquals("A4 점자 용지", service.update(USER_ID.toString(), of("  A4 점자 용지 ", null, null, null, null, null, null)).paperSize());
        assertEquals("10x11", service.update(USER_ID.toString(), of("  ", null, null, null, null, null, null)).paperSize());
    }

    @Test
    void 범위_밖_값은_COMMON4000이고_저장하지_않는다() {
        OutputOptions[] bad = {
                of(null, 3, null, null, null, null, null),          // 줄 4 미만
                of(null, 101, null, null, null, null, null),        // 줄 100 초과
                of(null, null, 7, null, null, null, null),          // 칸 8 미만
                of(null, null, 101, null, null, null, null),        // 칸 100 초과
                of(null, null, null, 0, null, null, null),          // 시작 번호 1 미만
                of(null, null, null, null, "none", null, null),     // 출력용에 「넣지 않음」은 없다
                of(null, null, null, null, null, null, "char"),     // 줄바꿈은 음절 · 어절뿐
                of("가".repeat(31), null, null, null, null, null, null)  // 용지 이름 30자 초과
        };
        for (OutputOptions o : bad) {
            CustomException e = assertThrows(CustomException.class, () -> service.update(USER_ID.toString(), o), o.toString());
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
        assertEquals(OutputOptions.defaults(), service.get(USER_ID.toString()), "거절한 값은 남지 않는다");
    }

    @Test
    void 없는_계정은_USER4001() {
        CustomException e = assertThrows(CustomException.class, () -> service.get(UUID.randomUUID().toString()));
        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    /** 검증 메서드(isValid)가 응답 · jsonb 에 "valid" 칸으로 새 나가지 않는다 (운영 확인에서 발견) */
    @Test
    void 직렬화하면_설정_칸만_나간다() throws Exception {
        String json = new ObjectMapper()
                .writeValueAsString(OutputOptions.defaults());

        Assertions.assertFalse(json.contains("\"valid\""), json);
        Assertions.assertTrue(json.contains("\"paperSize\""));
    }
}
