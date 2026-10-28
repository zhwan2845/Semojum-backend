package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PageResultRepository extends JpaRepository<PageResult, UUID> {
    Optional<PageResult> findByJobIdAndPageNumber(String jobId, int pageNumber);

    List<PageResult> findByJobIdOrderByPageNumber(String jobId);

    // 작업별 원가 합계 (운영자 실시간 모니터링 목록 — N+1 방지): [job_id, Σcost_krw, 미계상 포함 여부]
    @Query(value =
            "SELECT job_id, COALESCE(SUM(cost_krw), 0), BOOL_OR(cost_uncertain) " +
            "FROM page_results WHERE job_id IN (:jobIds) GROUP BY job_id", nativeQuery = true)
    List<Object[]> costSumsPerJob(@Param("jobIds") List<String> jobIds);
}
