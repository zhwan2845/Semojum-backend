package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.entity.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Redis 키 · 필드 문자열이 그대로인지 — 배포 순간 Redis 에 남아 있던 상태를 새 코드가 읽으므로 한 글자도 바뀌면 안 된다.
 * 기대값은 키를 한 곳으로 모으기 전 각 서비스에 흩어져 있던 리터럴 그대로다.
 */
class JobRedisKeysTest {

    @Test
    void 작업_상태_키와_필드_문자열이_예전과_같다() {
        assertEquals("job:abc-123:pages", JobRedisKeys.pages("abc-123"));
        assertEquals("job:abc-123:canceled", JobRedisKeys.cancelFlag("abc-123"));
        assertEquals("page:7", JobRedisKeys.pageField(7));
        assertEquals("page:", JobRedisKeys.PAGE_FIELD_PREFIX);
        assertEquals("total_pages", JobRedisKeys.TOTAL_PAGES);
    }

    @Test
    void 쪽_상태_묶음이_예전과_같다() {
        assertEquals(List.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED"), Page.TERMINAL_STATUSES);
        assertEquals(List.of("COMPLETED", "NEEDS_REVIEW"), Page.SUCCESS_STATUSES);
    }
}
