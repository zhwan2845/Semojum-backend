package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 이어서 처리의 DB 쿼리 — 실제 PostgreSQL(Testcontainers)에서 상태 전이 가드와 쪽 조회를 확인한다 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
class JobReopenRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired JobRepository jobRepository;
    @Autowired PageRepository pageRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    private Job finishedJob(String id, String status) {
        User user = userRepository.save(User.builder().loginId("u-" + id).password("x").build());
        Job job = jobRepository.save(Job.builder().id(id).user(user).mode("c").totalPages(3).originalFileName("a.pdf").build());
        jobRepository.finishJob(id, status, "{2,3}");
        return job;
    }

    @Test
    void 끝난_작업만_진행_중으로_돌리고_실패_쪽_목록을_비운다() {
        finishedJob("job-done", "COMPLETED");
        finishedJob("job-failed", "FAILED");

        assertEquals(1, jobRepository.reopenJob("job-done"));
        assertEquals(1, jobRepository.reopenJob("job-failed"));
        em.clear();
        Job reopened = jobRepository.findById("job-done").orElseThrow();
        assertEquals("IN_PROGRESS", reopened.getStatus());
        assertNull(reopened.getFinishedAt());
        assertNull(reopened.getFailedPages());
    }

    @Test
    void 진행_중인_작업은_돌리지_않는다_동시_요청_관문() {
        finishedJob("job-twice", "COMPLETED");

        assertEquals(1, jobRepository.reopenJob("job-twice"));
        assertEquals(0, jobRepository.reopenJob("job-twice"), "두 번째 요청은 0 — 서비스가 JOB4010으로 거절");
    }

    @Test
    void 크레딧_소진으로_막힌_쪽만_쪽_번호_순으로_찾는다() {
        Job job = finishedJob("job-pages", "COMPLETED");
        job = jobRepository.findById("job-pages").orElseThrow();
        for (int no : new int[]{3, 1, 2}) {
            Page page = Page.builder().job(job).pageNo(no).pdfPath("p" + no).build();
            if (no != 1) page.block(Page.BLOCKED_CREDIT_EXHAUSTED); else page.block(null);
            pageRepository.save(page);
        }

        List<Integer> found = pageRepository.findByJobAndBlockedReasonOrderByPageNoAsc(job, Page.BLOCKED_CREDIT_EXHAUSTED)
                .stream().map(Page::getPageNo).toList();
        assertEquals(List.of(2, 3), found);
    }

    /** 실패한 쪽 다시 변환의 대상 — 사유 없이 막힌 쪽만. 크레딧 소진 · 취소 · 완료 쪽은 빠진다 */
    @Test
    void 사유_없이_막힌_쪽만_다시_변환_대상이다() {
        finishedJob("job-retry", "COMPLETED");
        Job job = jobRepository.findById("job-retry").orElseThrow();
        String[] kinds = {"done", "fail", "credit", "cancel", "fail"};
        for (int i = 0; i < kinds.length; i++) {
            Page page = Page.builder().job(job).pageNo(i + 1).pdfPath("p" + i).build();
            switch (kinds[i]) {
                case "done" -> page.updateStatus("COMPLETED");
                case "fail" -> page.block(null);
                case "credit" -> page.block(Page.BLOCKED_CREDIT_EXHAUSTED);
                default -> page.block(Page.BLOCKED_CANCELED);
            }
            pageRepository.save(page);
        }

        assertTrue(pageRepository.existsByJobAndStatusAndBlockedReasonIsNull(job, "BLOCKED"));
        assertEquals(List.of(2, 5), pageRepository.findByJobAndStatusAndBlockedReasonIsNullOrderByPageNoAsc(job, "BLOCKED")
                .stream().map(Page::getPageNo).toList());
    }
}
