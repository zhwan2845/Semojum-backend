package com.semojum.backend.domain.job.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {
    List<Page> findByJobId(String jobId);
    Optional<Page> findByJobAndPageNo(Job job, int pageNo);

    // 워커용 — 태스크에는 jobId 문자열만 실려 있어 Job 엔티티를 다시 읽지 않고 바로 찾는다
    Optional<Page> findByJob_IdAndPageNo(String jobId, int pageNo);

    /** 이 사유로 막힌 쪽이 있는가 — 「크레딧 부족」 표시용 */
    boolean existsByJob_IdAndBlockedReason(String jobId, String blockedReason);

    List<Page> findByJobAndBlockedReasonOrderByPageNoAsc(Job job, String blockedReason);

    /** 실패한 쪽 다시 변환의 대상 — 사유 없이 막힌 쪽(AI 오류 · 시간 초과 · 재시도 초과). 크레딧 소진 · 취소는 사유가 있다 */
    boolean existsByJobAndStatusAndBlockedReasonIsNull(Job job, String status);

    List<Page> findByJobAndStatusAndBlockedReasonIsNullOrderByPageNoAsc(Job job, String status);
    long countByJobAndStatusIn(Job job, List<String> statuses);
    List<Page> findByJobAndStatus(Job job, String status);

    List<Page> findByJob(Job job);

    // 텍스트 작업의 쪽 수 = 남아 있는 마지막 쪽 번호
    @Query("SELECT MAX(p.pageNo) FROM Page p WHERE p.job.id = :jobId")
    Integer findMaxPageNo(@Param("jobId") String jobId);
}
