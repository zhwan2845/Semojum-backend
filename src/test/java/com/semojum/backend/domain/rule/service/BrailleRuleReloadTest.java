package com.semojum.backend.domain.rule.service;

import com.semojum.backend.domain.rule.dto.BrailleRuleDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.attribute.FileTime;

/**
 * braille-reference 의 rules.json 을 파일 경로로 읽고, 레포 갱신을 재시작 없이 반영한다.
 * 데이터는 저작권 문제로 BE 레포에 두지 않으므로 형식만 같은 작은 표본을 만들어 쓴다.
 */
class BrailleRuleReloadTest {

    @TempDir Path dir;

    private static String rule(String id, String name, String contents, String fullText, Boolean supported) {
        return "\"" + id + "\":{\"source\":\"한국 점자 규정\",\"publisher\":\"문화체육관광부\",\"version\":2024,"
                + "\"section\":{\"Paragraph\":\"제1항\"},\"rule_name\":\"" + name + "\",\"contents\":\"" + contents + "\","
                + "\"default_tag\":\"\""
                + (fullText == null ? "" : ",\"full_text\":\"" + fullText + "\"")
                + (supported == null ? "" : ",\"supported\":" + supported) + "}";
    }

    private Path write(String... rules) throws IOException {
        Path file = dir.resolve("rules.json");
        Files.writeString(file, "{\"_meta\":{},\"rules\":{" + String.join(",", rules) + "}}");
        return file;
    }

    /** 파일이 바뀐 걸 확실히 알아채도록 수정 시각을 옮긴다(같은 초 안의 덮어쓰기 대비) */
    private static void touchLater(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000));
    }

    @Test
    void 파일에서_전문과_점역_지원_여부를_읽는다() throws IOException {
        Path file = write(
                rule("MCST-한글-1.1.1", "기본 글자", "첫 문장.", "제1항 전문\\n예시 #a", true),
                rule("MCST-서양음악-1.1", "음이름", "음악 첫 문장.", "음악 전문", false),
                rule("MCST-한글-1.1.2", "예전 항목", "칸 없음.", null, null));
        BrailleRuleRegistry registry = new BrailleRuleRegistry(file.toString());
        registry.load();

        BrailleRule hangul = registry.findById("MCST-한글-1.1.1").orElseThrow();
        assertEquals("제1항 전문\n예시 #a", hangul.fullText());
        assertTrue(hangul.supported());
        assertFalse(registry.findById("MCST-서양음악-1.1").orElseThrow().supported());
        BrailleRule old = registry.findById("MCST-한글-1.1.2").orElseThrow();
        assertNull(old.fullText(), "전문이 없는 항목은 null");
        assertTrue(old.supported(), "supported 칸이 없던 예전 파일은 전부 점역 지원");
        assertEquals("MCST-서양음악-1.1", registry.all().get(2).ruleId(), "음악 편은 한글 편 뒤");
    }

    @Test
    void 전문에만_걸린_조항도_찾고_맨_아래로_보낸다() throws IOException {
        Path file = write(
                rule("MCST-한글-1.1.1", "된소리표", "첫 문장.", "된소리 예시 깜", true),
                rule("MCST-한글-1.1.2", "깜 약자", "다른 문장.", "전문", true));
        BrailleRuleRegistry registry = new BrailleRuleRegistry(file.toString());
        registry.load();
        BrailleRuleSearchService service = new BrailleRuleSearchService(registry);

        List<BrailleRuleDto.Item> items = service.search("깜", null, null, 0, 20).items();
        assertEquals(2, items.size());
        assertEquals("MCST-한글-1.1.2", items.get(0).ruleId(), "규정명 일치가 위");
        assertEquals("MCST-한글-1.1.1", items.get(1).ruleId());
        assertEquals("fullText", items.get(1).matchedIn());
        assertEquals("된소리 예시 깜", items.get(1).fullText());
    }

    @Test
    void 파일이_바뀌면_재시작_없이_다시_읽는다() throws IOException {
        Path file = write(rule("MCST-한글-1.1.1", "하나", "a", "a", true));
        BrailleRuleRegistry registry = new BrailleRuleRegistry(file.toString());
        registry.load();
        assertEquals(1, registry.all().size());

        write(rule("MCST-한글-1.1.1", "하나", "a", "a", true), rule("MCST-한글-1.1.2", "둘", "b", "b", true));
        touchLater(file);
        assertTrue(registry.refreshIfChanged());
        assertEquals(2, registry.all().size());
        assertTrue(registry.findById("MCST-한글-1.1.2").isPresent(), "id 색인도 함께 바뀐다");
    }

    @Test
    void 새_파일이_깨졌으면_기존_데이터를_유지한다() throws IOException {
        Path file = write(rule("MCST-한글-1.1.1", "하나", "a", "a", true));
        BrailleRuleRegistry registry = new BrailleRuleRegistry(file.toString());
        registry.load();

        Files.writeString(file, "{\"rules\": {깨진");
        touchLater(file);
        assertTrue(registry.refreshIfChanged(), "기존 데이터가 있으니 유효");
        assertEquals(1, registry.all().size());

        Files.writeString(file, "{\"rules\":{}}");
        touchLater(file);
        registry.refreshIfChanged();
        assertEquals(1, registry.all().size(), "빈 규정 파일로 검색을 비우지 않는다");
    }

    @Test
    void 파일이_없어도_서버는_뜬다() {
        BrailleRuleRegistry registry = new BrailleRuleRegistry(dir.resolve("없음.json").toString());
        assertDoesNotThrow(registry::load);
        assertTrue(registry.all().isEmpty());
    }
}
