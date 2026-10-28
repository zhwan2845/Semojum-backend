package com.semojum.backend.domain.support.repository;

import com.semojum.backend.domain.support.entity.Inquiry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {

    // 운영자 문의 목록 — 페이지네이션. 필터 조합별 4종
    Page<Inquiry> findAllByOrderByCreatedAtDesc(
            Pageable pageable);

    Page<Inquiry> findByStatusOrderByCreatedAtDesc(
            String status, Pageable pageable);

    Page<Inquiry> findByTypeOrderByCreatedAtDesc(
            String type, Pageable pageable);

    Page<Inquiry> findByStatusAndTypeOrderByCreatedAtDesc(
            String status, String type, Pageable pageable);

    // 기관 관리 화면 — 자기 기관의 요청·문의와 처리 상태
    List<Inquiry> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    // 메일 폴링 중복 방지 (DB 유니크가 최종 방어선)
    boolean existsByMailUid(String mailUid);
}
