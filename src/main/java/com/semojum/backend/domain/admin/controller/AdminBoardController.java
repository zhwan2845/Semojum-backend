package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.semojum.backend.domain.board.dto.BoardDto;
import com.semojum.backend.domain.board.service.BoardService;

/**
 * 운영자 API — QnA 게시판 답변 · 숨김 · 삭제.
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminBoardController {

    private final AdminAccessGuard adminAccessGuard;
    private final BoardService boardService;

    /** 글 목록 — 숨긴 글까지. waitingOnly = 「대기만 보기」(답변 없는 글) */
    @GetMapping("/board/posts")
    public ApiResponse<BoardDto.PostList> listBoardPosts(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean waitingOnly
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(boardService.listForAdmin(adminAccessGuard.currentUserId(), page, field, keyword, waitingOnly));
    }

    @PostMapping("/board/posts/{postId}/answers")
    public ApiResponse<BoardDto.Answer> createBoardAnswer(
            @PathVariable Long postId,
            @RequestBody BoardDto.CreateAnswer request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(boardService.createAnswer(adminAccessGuard.currentUserId(), postId, request));
    }

    /** 답변 수정 — 그 답변을 쓴 운영자만 */
    @PatchMapping("/board/answers/{answerId}")
    public ApiResponse<BoardDto.Answer> updateBoardAnswer(
            @PathVariable Long answerId,
            @RequestBody BoardDto.UpdateAnswer request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(boardService.updateAnswer(adminAccessGuard.currentUserId(), answerId, request));
    }

    @DeleteMapping("/board/answers/{answerId}")
    public ApiResponse<Void> deleteBoardAnswer(@PathVariable Long answerId) {
        adminAccessGuard.check();
        boardService.deleteAnswer(adminAccessGuard.currentUserId(), answerId);
        return ApiResponse.success(null);
    }

    @PatchMapping("/board/posts/{postId}/hidden")
    public ApiResponse<Void> updateBoardPostHidden(
            @PathVariable Long postId,
            @RequestBody BoardDto.UpdateHidden request
    ) {
        adminAccessGuard.check();
        boardService.updateHidden(postId, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/board/posts/{postId}")
    public ApiResponse<Void> deleteBoardPost(@PathVariable Long postId) {
        adminAccessGuard.check();
        boardService.deleteByAdmin(postId);
        return ApiResponse.success(null);
    }
}
