package com.semojum.backend.domain.job.worker;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 태스크 JSON의 원본 조각 경로 필드({@code sourcePath}) */
class TaskSourcePathTest {

    private Map<String, Object> task(String key, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("jobId", "job-1");
        m.put("pageNo", 1);
        if (key != null) m.put(key, value);
        return m;
    }

    @Test
    void 새_이름을_읽는다() {
        assertEquals("job-1/pages/page-1.pdf",
                PageWorker.sourcePathOf(task("sourcePath", "job-1/pages/page-1.pdf")));
    }

    /** 경로가 아예 없으면 null — 호출부의 재시도 경로가 받는다(무로그 증발 방지) */
    @Test
    void 경로가_없으면_null이다() {
        assertNull(PageWorker.sourcePathOf(task(null, null)));
    }
}
