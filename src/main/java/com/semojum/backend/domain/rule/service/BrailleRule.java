package com.semojum.backend.domain.rule.service;

/**
 * 점자 규정 한 건 (읽기 전용).
 *
 * <p>원본은 {@code Semojum/braille-reference} 의 rules.json — AI 팀 rule registry(정본)에서 만든
 * 산출물이라 rule_id 집합이 registry 와 같다(AI 가 보내는 rule_trail.rule_id ⊆ 이 집합).
 */
public record BrailleRule(
        String ruleId,
        String publisherCode,   // MCST | NLD | NISE — rule_id 접두와 같다
        String publisher,
        String source,
        int version,
        String part,            // 없는 위계는 null
        String chapter,
        String section,
        String paragraph,
        String sectionDisplay,  // 위 4단계를 " · " 로 합친 표시용 한 줄 (rule_trail.section 과 같은 형식)
        String ruleName,
        String contents,
        String tag,
        String fullText,        // 조항 전문(예시 · 붙임 포함, 원문 줄바꿈 그대로). 점자 예시는 ASCII 점자. 없으면 null
        boolean supported,      // false = 점역 미지원 · 검색 전용(음악 · 국제음성기호) — 화면에 표시해야 한다
        int displayOrder,       // 원문 순서. 파일 키 순서는 문자열 정렬이라 쓸 수 없다(1.4.10 < 1.4.8)
        String searchBlob       // 소문자 합본 — 검색은 이 한 칸만 본다
) {}
