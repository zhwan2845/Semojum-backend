package com.semojum.backend.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
/** 에러 코드 정본 — 코드 · 메시지는 노션 API 명세 공통 에러 표와 맞춘다. 앱 · 콘솔이 코드로 분기하므로 바꾸지 않는다 */
@Getter
public enum ErrorCode {

    // ========== 공통 ==========
    COMMON_BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON4000", "잘못된 요청입니다."),
    COMMON_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON4001", "인증이 필요합니다."),
    COMMON_FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON4003", "권한이 없습니다."),
    COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON4004", "요청한 경로를 찾을 수 없습니다."),
    COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON4005", "지원하지 않는 요청 방식입니다."),
    COMMON_NAME_DUPLICATE(HttpStatus.CONFLICT, "COMMON4009", "같은 이름이 이미 있습니다."),   // 점역 양식 등
    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON5000", "서버 에러, 관리자에게 문의 바랍니다."),

    // ========== 인증 (AUTH) ==========
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH4001", "아이디 또는 비밀번호가 올바르지 않습니다."),
    AUTH_DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "AUTH4002", "이미 사용 중인 로그인 ID입니다."),
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH4003", "액세스 토큰이 만료되었거나 유효하지 않습니다."),
    AUTH_WRONG_CHANNEL(HttpStatus.FORBIDDEN, "AUTH4005", "이 환경에서는 로그인할 수 없는 계정입니다."),
    // 로그인 5회 실패 → 10분 잠금 — result.unlockAt에 해제 시각
    AUTH_LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "AUTH4006", "로그인을 5회 실패해 10분 동안 잠겼습니다. 잠시 후 다시 시도해 주세요."),

    // ========== 유저 (USER) ==========
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER4001", "존재하지 않는 회원입니다."),

    // ========== 기관 (ORG) ==========
    AUTH_INACTIVE_ACCOUNT(HttpStatus.FORBIDDEN, "AUTH4004", "비활성화된 계정입니다."),
    ORG_NOT_FOUND(HttpStatus.NOT_FOUND, "ORG4001", "존재하지 않는 기관입니다."),
    ORG_CODE_DUPLICATE(HttpStatus.CONFLICT, "ORG4002", "이미 사용 중인 기관 코드입니다."),

    // ========== 작업 (JOB) ==========
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "JOB4001", "존재하지 않는 작업입니다."),
    JOB_INVALID_FILE(HttpStatus.BAD_REQUEST, "JOB4002", "PDF·HWP·HWPX·TXT 파일만 업로드 가능합니다."),
    JOB_INVALID_MODE(HttpStatus.BAD_REQUEST, "JOB4003", "지원하지 않는 모드입니다."),
    ELEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "JOB4004", "존재하지 않는 요소입니다."),
    // JOB4005(elementType 검증)는 폐기 — 번호를 다시 쓰지 않는다
    ELEMENT_LIST_MISMATCH(HttpStatus.BAD_REQUEST, "JOB4006", "요소 목록이 현재 페이지의 요소와 일치하지 않습니다."),
    JOB_HWP_PARSE_FAILED(HttpStatus.BAD_REQUEST, "JOB4007", "HWP 파일을 읽을 수 없습니다. 파일이 손상되었거나 지원하지 않는 형식입니다."),
    JOB_HWP_UNSUPPORTED(HttpStatus.BAD_REQUEST, "JOB4008", "암호가 설정되었거나 배포용으로 저장된 HWP 파일은 변환할 수 없습니다."),
    JOB_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "JOB4009", "업로드 가능한 파일 최대 용량(100MB)을 초과했습니다."),
    JOB_IN_PROGRESS(HttpStatus.CONFLICT, "JOB4010", "변환 중인 작업은 이동·이름변경·삭제·다운로드할 수 없습니다."),
    // JOB4011(점역 문서 덮어쓰기 확인)은 쓰인 적이 없어 지웠다 — 번호를 다시 쓰지 않는다
    JOB_HWP_CONVERT_FAILED(HttpStatus.BAD_REQUEST, "JOB4013", "HWP를 PDF로 변환하지 못했습니다. 파일을 확인해 주세요."),
    // 이어서 처리 — 크레딧 소진으로 멈춘 쪽 재개
    JOB_NOTHING_TO_RESUME(HttpStatus.CONFLICT, "JOB4014", "이어서 처리할 쪽이 없습니다."),
    // 이어서 처리 · 실패한 쪽 다시 변환 공통
    JOB_COPY_NOT_RESUMABLE(HttpStatus.BAD_REQUEST, "JOB4015", "받은 파일로 만든 사본에서는 변환을 다시 돌릴 수 없습니다. 원래 작업에서 해 주세요."),
    // 실패한 쪽 다시 변환
    JOB_NOTHING_TO_RETRY(HttpStatus.CONFLICT, "JOB4016", "다시 변환할 실패한 쪽이 없습니다."),

    // ========== 크레딧 (CREDIT) ==========
    CREDIT_INSUFFICIENT(HttpStatus.PAYMENT_REQUIRED, "CREDIT4001", "잔여 크레딧이 없습니다. 크레딧을 충전한 뒤 다시 시도해 주세요."),

    // FOLDER4001~4004는 폴더 기능을 없애며 폐기 — 번호를 다시 쓰지 않는다

    // ========== 게시판 (BOARD) ==========
    BOARD_POST_NOT_FOUND(HttpStatus.NOT_FOUND, "BOARD4001", "존재하지 않는 글입니다."),
    BOARD_NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "BOARD4002", "존재하지 않는 공지입니다."),
    BOARD_ANSWER_NOT_FOUND(HttpStatus.NOT_FOUND, "BOARD4003", "존재하지 않는 답변입니다."),
    BOARD_ATTACHMENT_INVALID(HttpStatus.BAD_REQUEST, "BOARD4004", "첨부할 수 없는 파일입니다. 최대 5개, 이미지·문서·zip만 올릴 수 있습니다."),
    BOARD_ATTACHMENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "BOARD4130", "첨부 파일은 한 개에 10MB까지 올릴 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}