package com.semojum.backend.domain.job.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class JobResponseDto {

    public record Create(
            String jobId,
            String mode,
            int totalPages,
            String status,
            boolean insertPageNumber,
            String footerText,
            // 이 작업에 적용된 조판 옵션 — 안 보낸 항목이 기본값으로 채워진 최종 형태
            LayoutOptions layoutOptions
    ) {}

    /**
     * 이 작업의 업로드 설정 — 조판 옵션 + 꼬리말 (같은 옵션 화면에서 함께 고르는 값들).
     * 에디터가 작업을 열 때 설정을 복원하는 용도. 페이지 조회를 거치지 않고 바로 볼 수 있다.
     */
    public record Options(
            String jobId,
            boolean insertPageNumber,
            String footerText,
            // 위 꼬리말의 점역 결과 — 서버가 점역하던 예전 작업만 값이 있다. 새 작업은 null(앱이 점역)
            String footerBraille,
            LayoutOptions layoutOptions,
            // 그 작업에 적용된 점역 옵션 — 에디터가 열 때 점역 설정을 복원한다.
            // 점역 옵션이 생기기 전 작업은 공장 기본값이 채워져 내려온다
            BrailleOptions brailleOptions
    ) {}

    /**
     * 이어서 처리 응답 — 이후 진행은 SSE({@code /events}) 나 {@code /status} 로 본다.
     *
     * @param resumedPages 다시 큐에 넣은 쪽 번호(오름차순)
     */
    public record Resume(String jobId, String status, int totalPages, List<Integer> resumedPages) {}

    /**
     * 실패한 쪽 다시 변환 응답 — 이후 진행은 SSE({@code /events}) 나 {@code /status} 로 본다.
     *
     * @param retriedPages 다시 큐에 넣은 쪽 번호(오름차순)
     */
    public record Retry(String jobId, String status, int totalPages, List<Integer> retriedPages) {}

    public record Status(
            String jobId,
            int totalPages,
            int completedPages,
            int pendingPages,
            int runningPages,
            String overallStatus,
            Map<String, String> pages,
            // 기관 잔여 크레딧이 떨어져 AI에 보내지 않고 막은 쪽이 있는가 — 앱이 「크레딧 부족」을 알린다
            boolean creditExhausted
    ) {}

    /**
     * 재시작 복구용 진행 중 작업 — 어느 작업의 몇 페이지로 돌아갈지 판단할 값만 담는다.
     */
    public record ActiveJob(
            String jobId,
            String mode,
            String status,
            int totalPages,
            Integer progress,
            String originalFileName,
            LocalDateTime lastModifiedAt,
            Integer lastEditedPage
    ) {}

    public record JobDetail(
            String jobId,
            String mode,
            String status,
            int totalPages,
            int[] failedPages,
            String originalFileName,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            int pageNo,
            boolean insertPageNumber,
            // 업로드 때 고른 조판 옵션 — 에디터가 다음에 열 때도 같은 설정으로 열도록
            LayoutOptions layoutOptions,
            // 이 면의 페이지행에 들어갈 점역된 꼬리말 — 서버가 점역하던 예전 작업만 값이 있다. 없으면 null
            String footerBraille,
            Map<String, Object> result,
            OriginalContent original,
            // 이 쪽의 줄 메모 — 없으면 빈 배열. 쓰기는 작업 저장 본문이다
            List<Memo> memos
    ) {}

    /**
     * 줄 메모. {@code lineText}는 <b>그 줄의 원문</b>이지 메모 내용이 아니다 —
     * 조판 설정이 바뀌어 {@code lineIndex}가 어긋났을 때 FE가 이것으로 다시 찾는다.
     */
    public record Memo(
            Long memoId,
            String panel,
            String elementId,
            int lineIndex,
            String lineText,
            String body,
            LocalDateTime createdAt,
            String author,             // 작성자 표시 이름. 예전 메모는 null
            boolean resolved,          // 해결 여부
            LocalDateTime resolvedAt,  // 해결 시각. 미해결이면 null
            List<MemoReply> replies    // 답글 — 없으면 빈 배열
    ) {}

    public record MemoReply(Long replyId, String author, String body, LocalDateTime createdAt) {}

    // 페이지별 원본. URL은 하나이고 무엇인지는 type이 알려준다.
    //   type="image" : a·c 정상 — 서버가 미리 렌더한 JPEG. FE는 <img>로 바로 그린다
    //   type="pdf"   : a·c인데 이미지가 없을 때(렌더 실패·page-image 비활성) — FE는 pdf.js로 그린다
    //   type="text"  : b — url 없이 lines(원문 줄 배열)
    // 사실상 항상 image이지만, 렌더가 실패해도 원본 패널이 비지 않도록 pdf 경로를 남겨 둔다.
    //
    // NON_NULL: 그 모드에 해당 없는 필드는 키 자체를 빼 응답을 명확하게 한다
    //   a·c → {type, url}  /  b → {type, lines}
    // (result 안의 repeated 필드는 여전히 항상 배열로 나간다 — PageResultSerializer 담당, 이 설정과 무관)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OriginalContent(
            String type,
            String url,
            List<String> lines
    ) {}

    /**
     * 작업 저장 결과.
     *
     * <p>쪽 단위 트랜잭션이라 일부만 실패할 수 있다. {@code failedPages}가 비어 있으면 전부 성공이고,
     * null이 아니라 빈 배열로 나간다 — FE가 null 검사 없이 순회하도록.
     */
    public record SaveResult(
            String jobId,
            int savedPages,
            List<Integer> failedPages,
            LocalDateTime savedAt,
            // 받은 .smj 파일이라 사본을 만들어 저장했으면 원래 작업 ID. 그때 jobId는 새 사본이다 —
            // 앱은 파일의 작업 ID를 jobId로 바꿔 이후 저장을 사본으로 보낸다. 내 작업이면 null
            String copiedFromJobId,
            // 쪽별 결과 — 실패한 쪽이 왜 실패했는지. 보낸 쪽 순서대로
            List<PageSaveResult> pageResults
    ) {}

    /**
     * @param status    SAVED | FAILED
     * @param errorCode 실패 사유 — 공개 에러 코드(예: COMMON4000 형식 · JOB4006 블록 id 중복). 알 수 없는 오류는
     *                  COMMON5000(일시 장애일 수 있어 다시 보내 볼 만하다). 성공이면 null
     */
    public record PageSaveResult(int pageNo, String status, String errorCode,
                                 // 이번 저장으로 새로 만든 메모의 서버 id. 없거나 실패한 쪽이면 빈 배열
                                 List<MemoIdMapping> memoIds) {
        public PageSaveResult(int pageNo, String status, String errorCode) {
            this(pageNo, status, errorCode, List.of());
        }
    }

    /**
     * 새로 만든 메모의 id 대응 — 앱은 파일의 메모를 이 memoId 로 바꿔 다음 저장부터 그 id 로 보낸다.
     *
     * @param clientMemoId   앱이 보낸 임시 id(없으면 null)
     * @param previousMemoId 앱이 memoId 를 보냈는데 이 쪽에 없던 경우 그 값 — 받은 파일의 사본 작업처럼 id 가 다른
     *                       작업에서 온 메모다. 아니면 null
     * @param memoId         서버가 발급한 id
     */
    public record MemoIdMapping(String clientMemoId, Long previousMemoId, Long memoId) {}
}
