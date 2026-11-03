package com.semojum.backend.global.category;

import java.util.Set;

/**
 * 게시글 · 문의 분류 — 피그마 게시판 글쓰기 · 문의하기 · 마이페이지 문의 내역이 같은 네 가지를 쓴다.
 *
 * <p>값은 DB · API에 그대로 나간다. 화면 이름은 앱이 붙인다.
 */
public final class QuestionCategory {

    public static final String ACCOUNT = "ACCOUNT";   // 계정 · 발급
    public static final String RESULT = "RESULT";     // 점역 결과
    public static final String BILLING = "BILLING";   // 크레딧 · 결제
    public static final String USAGE = "USAGE";       // 사용법

    public static final Set<String> ALL = Set.of(ACCOUNT, RESULT, BILLING, USAGE);

    private QuestionCategory() {}
}
