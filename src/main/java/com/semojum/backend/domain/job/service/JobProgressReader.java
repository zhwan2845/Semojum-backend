package com.semojum.backend.domain.job.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import com.semojum.backend.domain.job.entity.Page;

/**
 * 변환 중 작업의 완료 페이지 수를 Redis 상태 Hash에서 읽는다.
 * 기관 관리자 계정 상세 · 사용량의 "진행 중 n/m쪽" 표시용 — Redis 장애 시 null 격하(화면은 "변환 중"으로만).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobProgressReader {

    private final RedisTemplate<String, String> redisTemplate;

    public Integer donePages(String jobId) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(JobRedisKeys.pages(jobId));
            if (entries.isEmpty()) return null;
            int done = 0;
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                if (String.valueOf(e.getKey()).startsWith(JobRedisKeys.PAGE_FIELD_PREFIX)
                        && Page.TERMINAL_STATUSES.contains(String.valueOf(e.getValue()))) {
                    done++;
                }
            }
            return done;
        } catch (Exception e) {
            log.warn("진행 페이지 조회 실패(무시): jobId={}, error={}", jobId, e.getMessage());
            return null;
        }
    }
}
