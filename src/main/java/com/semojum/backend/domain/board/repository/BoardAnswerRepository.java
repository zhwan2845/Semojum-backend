package com.semojum.backend.domain.board.repository;

import com.semojum.backend.domain.board.entity.BoardAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BoardAnswerRepository extends JpaRepository<BoardAnswer, Long> {

    List<BoardAnswer> findByPostIdOrderByIdAsc(Long postId);

    // 목록 제목 뒤 (N) — 쪽 단위 한 번에 센다(글마다 세면 N+1)
    // 운영자 답변만 센다 — 글쓴이가 이어 쓴 글은 답변이 아니다
    @Query("SELECT a.postId, COUNT(a) FROM BoardAnswer a WHERE a.postId IN :postIds AND a.fromAuthor = false GROUP BY a.postId")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);

    // 글마다 마지막 글이 글쓴이 것인지 — 처리 상태(마지막이 운영자 답변이면 답변 완료). [postId, fromAuthor]
    @Query("SELECT a.postId, a.fromAuthor FROM BoardAnswer a WHERE a.id IN " +
            "(SELECT MAX(b.id) FROM BoardAnswer b WHERE b.postId IN :postIds GROUP BY b.postId)")
    List<Object[]> findLastFromAuthorByPostIds(@Param("postIds") Collection<Long> postIds);
}
