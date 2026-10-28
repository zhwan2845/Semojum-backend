package com.semojum.backend.domain.job.service;

/**
 * 작업 진행 상태의 Redis 키 · Hash 필드 이름을 한 곳에 둔다.
 *
 * <p>⚠️ 문자열을 바꾸지 말 것 — 배포 순간 Redis 에 남아 있던 상태를 새 코드가 그대로 읽는다.
 * 스케줄러 큐 · 링 키는 {@code JobDispatcher}가 갖고 있다.
 */
public final class JobRedisKeys {

    /** 상태 Hash 의 전체 쪽 수 필드 */
    public static final String TOTAL_PAGES = "total_pages";
    /** 상태 Hash 의 쪽 필드 접두어 — {@code page:{번호}} */
    public static final String PAGE_FIELD_PREFIX = "page:";

    private JobRedisKeys() {}

    /** 쪽별 상태 Hash {@code job:{jobId}:pages} (종료 시 TTL 1h) */
    public static String pages(String jobId) {
        return "job:" + jobId + ":pages";
    }

    /** 취소 플래그 {@code job:{jobId}:canceled} (TTL 1h) */
    public static String cancelFlag(String jobId) {
        return "job:" + jobId + ":canceled";
    }

    /** 상태 Hash 안 쪽 하나의 필드 {@code page:{pageNo}} */
    public static String pageField(int pageNo) {
        return PAGE_FIELD_PREFIX + pageNo;
    }
}
