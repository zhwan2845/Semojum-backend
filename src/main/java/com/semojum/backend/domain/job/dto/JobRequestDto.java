package com.semojum.backend.domain.job.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.time.LocalDateTime;

public class JobRequestDto {

    public record Create(
            @NotBlank String mode
    ) {}

    /**
     * id=null이면 사용자 작성 새 블록(서버가 id 발급, type은 항상 "text" — 사용자가 만들 수 있는 블록은 텍스트뿐).
     *
     * <p>{@code selectedIdx}는 대체 초안 중 어느 것을 골랐는지다 — null이면 그대로 두고,
     * -1은 AI 원본 복귀, 0 이상은 그 초안. 본문({@code contents})은 FE가 이미 그 초안으로 채워 보내므로
     * 서버는 <b>어느 것을 골랐는지만 기록</b>한다. 조회 응답의 {@code selected_idx}가 이 값이다.
     */
    public record SaveElement(
            String id,
            @NotNull List<String> contents,
            Integer selectedIdx
    ) {}


    /**
     * 작업 설정 변경 — 최종 저장 본문의 설정 항목을 {@code JobOptionsService}에 넘길 때 쓴다(별도 API 없음).
     *
     * <p><b>전부 선택이고, 보낸 항목만 바뀐다.</b> null·미전송은 "그대로 두라"는 뜻이다
     * ({@code layoutOptions} 안의 12개 항목도 같은 규칙 — {@link LayoutOptions#merge}).
     *
     * <p>꼬리말만 예외적으로 지울 수 있다 — {@code footerText: ""}를 보내면 삭제된다.
     * null(미전송)과 구분해야 해서 빈 문자열에 그 뜻을 준다.
     */
    public record UpdateOptions(
            Boolean insertPageNumber,
            String footerText,
            LayoutOptions layoutOptions,
            BrailleOptions brailleOptions
    ) {}

    /**
     * 작업 저장 — 유일한 저장 경로. 본문은 <b>작업 최종 상태 전체</b>다.
     *
     * <p>FE 점역 엔진이 텍스트·점자를 모두 만들어 보내므로 저장 경로에서 AI를 부르지 않는다.
     * 블록 추가·삭제·순서는 서버가 판정한다 — 배열 순서가 곧 읽기 순서고, 배열에서 빠진
     * 기존 블록은 삭제, id가 null인 항목은 새 블록이다.
     */
    public record SaveJob(
            Boolean insertPageNumber,
            String footerText,
            LayoutOptions layoutOptions,
            BrailleOptions brailleOptions,
            // 중첩 검증(@Valid) — 쪽 · 블록 · 메모 · 답글의 필수값까지 쓰기 전에 거절한다
            @NotNull List<@NotNull @Valid SaveJobPage> pages,
            // 텍스트 작업(TXT · 새 문서)의 전체 쪽 수 — 보내면 그 뒤 쪽을 지운다(쪽 줄이기). 안 보내면 쪽 수를
            // 줄이지 않는다(실패한 쪽 때문에 쪽 수가 줄지 않게). PDF · 이미지 작업은 보내면 400
            Integer totalPages
    ) {}

    /** 한 쪽의 최종 상태. 그 모드에 없는 패널은 null(TXT·새 문서엔 텍스트만) */
    public record SaveJobPage(
            int pageNo,
            List<@NotNull @Valid SaveElement> text,
            List<@NotNull @Valid SaveElement> braille,
            List<@NotNull @Valid SaveMemo> memos
    ) {}

    /**
     * 줄 메모. {@code lineText}는 <b>그 줄의 원문</b>이지 메모 내용이 아니다 —
     * 줄이 밀렸을 때 다시 찾기 위한 단서다. {@code memoId}를 보내면 작성 시각이 보존된다.
     */
    public record SaveMemo(
            Long memoId,
            String panel,
            String elementId,
            int lineIndex,
            String lineText,
            @NotNull String body,
            LocalDateTime createdAt,
            String author,                         // 작성자 표시 이름 — 앱이 보낸 값 그대로 저장
            Boolean resolved,                      // 해결 여부. 안 보내면 미해결
            LocalDateTime resolvedAt,    // 해결 시각. 해결인데 없으면 서버 시각
            List<@NotNull @Valid SaveMemoReply> replies,  // 답글 — 이 메모의 최종 상태 전체. 없으면 답글 없음
            // 앱이 새 메모에 붙이는 임시 id. 저장 응답 pageResults[].memoIds 가 이 값으로
            // 서버가 발급한 memoId 를 알려 준다. 서버에 있는 메모(memoId)는 안 보내도 된다
            String clientMemoId
    ) {
        /** clientMemoId 가 생기기 전 호출부 호환 */
        public SaveMemo(Long memoId, String panel, String elementId, int lineIndex, String lineText, String body,
                        LocalDateTime createdAt, String author, Boolean resolved,
                        LocalDateTime resolvedAt, List<SaveMemoReply> replies) {
            this(memoId, panel, elementId, lineIndex, lineText, body, createdAt, author, resolved, resolvedAt, replies, null);
        }
    }

    /** 메모 답글 — .smj를 받은 사람이 단다. 작성자 · 작성 시각은 파일에 든 값을 그대로 보낸다 */
    public record SaveMemoReply(
            String author,
            @NotNull String body,
            LocalDateTime createdAt
    ) {}

    /**
     * 새 문서 — 파일 없이 작업을 만든다(파일 › 새 문서). TXT 업로드도 이걸 쓰면 된다.
     *
     * <p>서버는 본문을 쓰지 않는다 — 점역·쪽 나누기는 앱이 하고, 내용은 첫 저장에 실려 온다.
     * {@code text}는 구 클라이언트 호환으로 받기만 한다(보내지 않아도 된다).
     * 결과는 텍스트 · 점자 2패널이고 원본 패널이 없다.
     */
    public record NewDocument(
            String text,
            String fileName,
            Boolean insertPageNumber,
            String footerText,
            LayoutOptions layoutOptions,
            String formId,
            BrailleOptions brailleOptions
    ) {}
}
