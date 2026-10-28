package com.semojum.backend.domain.rule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 점자 규정 검색 응답 */
public class BrailleRuleDto {

    /**
     * 검색 결과 한 건. section 은 rule_trail 의 section 과 <b>같은 " · " 합본 형식</b>이라
     * 에디터 규정 배지에서 본 문자열이 검색 결과에도 그대로 보인다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String ruleId,
            String publisherCode,   // MCST | NLD | NISE
            String publisher,       // 문화체육관광부 | 국립장애인도서관 | 국립특수교육원
            String source,          // 한국 점자 규정 | 점자 도서 제작 지침 | 점자 자료 제작 지침
            int version,            // 2024 | 2025
            String section,         // "한글 점자 · 제5장. 숫자와 기호 · 제11절. … · 제40항"
            String ruleName,
            String contents,
            String tag,             // default_tag (없으면 미포함)
            // 조항 전문 — 펼친 자리에 보여 준다. 예시 · 붙임까지 들어 있고 줄바꿈은 원문 PDF 그대로,
            // 점자 예시는 ASCII 점자(#a 꼴)다. 목록 미리보기는 contents 를 쓴다. 없으면 미포함
            String fullText,
            boolean supported,      // false = 점역 미지원 · 검색 전용(음악 · 국제음성기호) — 「점역 미지원」 표시 필요
            String matchedIn        // ruleId | ruleName | section | contents | fullText — FE 하이라이트 대상
    ) {}

    /** 운영자 문의 목록과 같은 페이지 응답 규약 */
    public record Page(
            List<Item> items,
            int page, int size,
            long totalElements, int totalPages
    ) {}
}
