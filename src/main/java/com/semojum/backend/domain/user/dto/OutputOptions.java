package com.semojum.backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 출력용 점자문서 설정 — 계정 기본값 (설정 › 출력용 점자문서 페이지설정).
 *
 * <p>문서 최적화가 편집한 문서를 이 설정으로 다시 조판해 출력용 .brf를 만든다 — 조판은 앱이 하고
 * 서버는 <b>보관 · 검증만</b> 한다. 창의 「설정 저장(기본값으로 저장)」이 계정에 남겨 다른 PC에서도 같게 한다.
 * 저장은 {@code users.output_options}(jsonb) 한 칸.
 *
 * <p>모르는 필드는 무시한다 — 앱이 먼저 새 항목을 보내도 서버가 죽지 않게.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutputOptions(
        // 점자 인쇄 용지 — 이름만 보관한다. 기획에 규격 목록이 없어(예시는 10×11인치 하나) 허용값을 두지 않고,
        // 용지에 따른 줄 수 · 칸 수 자동 입력은 앱이 한다
        String paperSize,
        Integer linesPerPage,       // 페이지당 줄 수 (기본 26)
        Integer cellsPerLine,       // 줄당 칸 수 (기본 32)
        Integer braillePageStart,   // 점자 페이지 시작 번호 (기본 1)
        String pageNumberLine,      // 점자 페이지 번호 출력: odd(홀수 페이지) | every(모든 페이지). 기본 odd
        Boolean duplex,             // 양면 출력 (기본 true). false = 단면
        String lineBreakUnit        // 줄바꿈 방식: syllable(음절) | word(어절). 기본 syllable
) {
    public static final String DEFAULT_PAPER_SIZE = "10x11";
    public static final int DEFAULT_LINES_PER_PAGE = 26;
    public static final int DEFAULT_CELLS_PER_LINE = 32;
    public static final String DEFAULT_PAGE_NUMBER_LINE = "odd";
    public static final String DEFAULT_LINE_BREAK_UNIT = "syllable";
    public static final int PAPER_SIZE_MAX = 30;

    /** 빠진 값을 기본값으로 채운 사본. 용지 이름은 앞뒤 공백을 걷고, 비었으면 기본 용지 */
    public OutputOptions withDefaults() {
        return new OutputOptions(
                paperSize == null || paperSize.isBlank() ? DEFAULT_PAPER_SIZE : paperSize.trim(),
                linesPerPage == null ? DEFAULT_LINES_PER_PAGE : linesPerPage,
                cellsPerLine == null ? DEFAULT_CELLS_PER_LINE : cellsPerLine,
                braillePageStart == null ? 1 : braillePageStart,
                pageNumberLine == null ? DEFAULT_PAGE_NUMBER_LINE : pageNumberLine,
                duplex == null || duplex,
                lineBreakUnit == null ? DEFAULT_LINE_BREAK_UNIT : lineBreakUnit);
    }

    /** 공장 기본값 — 10×11인치 · 26줄 × 32칸 · 홀수 페이지 번호 · 양면 · 음절 */
    public static OutputOptions defaults() {
        return new OutputOptions(null, null, null, null, null, null, null).withDefaults();
    }

    /**
     * 값이 허용 범위인가 — {@link #withDefaults()}를 거친 값에 쓴다.
     * 줄 · 칸 수 범위는 편집용 조판 옵션과 같다(칸 8~100 · 줄 4~100).
     */
    @JsonIgnore   // is 로 시작해 Jackson 이 응답 · jsonb 에 "valid" 칸으로 내보냈다
    public boolean isValid() {
        return paperSize.length() <= PAPER_SIZE_MAX
                && linesPerPage >= 4 && linesPerPage <= 100
                && cellsPerLine >= 8 && cellsPerLine <= 100
                && braillePageStart >= 1
                && List.of("odd", "every").contains(pageNumberLine)
                && List.of("syllable", "word").contains(lineBreakUnit);
    }
}
