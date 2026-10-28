package com.semojum.backend.domain.auth.repository;

import com.semojum.backend.domain.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByLoginId(String loginId);

    // 기관 소속 계정 목록 (기관 관리자 소속 계정 표 — 발급 순서 = loginId 순, 삭제 계정 제외)
    List<User> findByOrganizationIdAndDeletedAtIsNullOrderByLoginIdAsc(UUID organizationId);

    // 삭제 안 된 전체 소속 계정 (운영자 기관·계정 통합 표)
    List<User> findByOrganizationIdIsNotNullAndDeletedAtIsNullOrderByLoginIdAsc();

    // 계정 일괄 발급 시 다음 순번 계산용 — 기관 코드 프리픽스로 시작하는 loginId 전체
    @Query("select u.loginId from User u where u.loginId like concat(:prefix, '%')")
    List<String> findLoginIdsByPrefix(@Param("prefix") String prefix);

}
