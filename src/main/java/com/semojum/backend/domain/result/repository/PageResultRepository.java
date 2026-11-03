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

    /** 이 작업의 최근 쪽 결과 시각(내림차순, 최대 10개) — 잔여 시간의 실제 처리 속도 */
    @Query(value = "SELECT created_at FROM page_results WHERE job_id = :jobId ORDER BY created_at DESC LIMIT 10",
            nativeQuery = true)
    List<java.sql.Timestamp> findRecentResultTimes(@Param("jobId") String jobId);

    /** 최근 200쪽의 평균 AI 처리 시간(ms) — 잔여 시간 첫 추정. 기록이 없으면 null */
    @Query(value = "SELECT AVG(processing_time_ms) FROM (SELECT processing_time_ms FROM page_results " +
            "WHERE processing_time_ms IS NOT NULL ORDER BY created_at DESC LIMIT 200) recent", nativeQuery = true)
    Double averageRecentProcessingMs();
}
