package com.semojum.backend.domain.rule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 점자 규정 표를 메모리에 상주시킨다.
 *
 * <p>데이터는 {@code Semojum/braille-reference} 의 {@code rules.json}(340건 — 점역 지원 239 · 검색 전용 101,
 * 조항 전문 포함)이다. 운영은 EC2 에 clone 한 폴더를 컨테이너에 연결해 {@code rules.path} 로 읽고,
 * 레포 갱신(EC2 cron 이 git pull)을 <b>1분 안에 재시작 없이</b> 반영한다.
 *
 * <p>⚠️ 이미지에 넣지 않는다 — 지침 두 권의 원문이라 레포를 비공개로 두고(README §3), 갱신을 배포와 떼어 둔다.
 *
 * <p>DB 가 아닌 이유 — 순수 읽기 전용 참조 데이터이고, 정본이 AI 팀 registry 라 레포 한 곳에서
 * 받아 쓰는 편이 어긋나지 않는다. rule_trails 는 이미 스냅샷을 갖고 있어 조인할 대상도 없다.
 * 실측: 로드 17ms / 검색 1회 12~41µs (239건 기준).
 *
 * <p>비우면 클래스패스 {@value #RESOURCE} 를 읽는다 — 테스트 리소스에만 있다(운영 이미지에는 없다).
 */
@Slf4j
@Component
public class BrailleRuleRegistry {

    static final String RESOURCE = "/rules/braille-rules.json";

    /** 우선순위 P1 MCST > P2 NLD > P3 NISE (registry _meta 의 priority) */
    private static final Map<String, Integer> PUBLISHER_RANK = Map.of("MCST", 1, "NLD", 2, "NISE", 3);

    /** MCST 는 편이 나뉘어 있고 rule_id 중간 세그먼트가 편 이름이다. 음악 · 음성기호는 검색 전용 */
    private static final Map<String, Integer> MCST_PART_RANK = Map.of(
            "기본", 1, "한글", 2, "수학", 3, "과학", 4, "외국어", 5,
            "한국음악", 6, "서양음악", 7, "음성기호", 8);

    /** 위계 순서 — 없는 단계는 JSON 에 키 자체가 없다 */
    private static final List<String> SECTION_KEYS = List.of("Part", "Chapter", "Section", "Paragraph");

    /** 규정 · id 색인을 한 번에 바꿔 끼운다 — 검색 도중 갱신돼도 둘이 어긋나지 않는다 */
    private record Snapshot(List<BrailleRule> rules, Map<String, BrailleRule> byId) {}

    private final String path;
    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of());
    /** 마지막으로 읽은 파일의 수정 시각 · 크기 — 바뀌었을 때만 다시 읽는다 */
    private long loadedModified = -1;
    private long loadedSize = -1;

    @Autowired
    public BrailleRuleRegistry(@Value("${rules.path:}") String path) {
        this.path = path == null ? "" : path.trim();
    }

    /** 테스트용 — 클래스패스 리소스를 읽는다 */
    public BrailleRuleRegistry() {
        this("");
    }

    /**
     * 기동 시 읽는다. 파일이 없거나 깨져도 서버는 띄운다(빈 목록 + ERROR) — 규정 검색 하나 때문에
     * 배포 헬스 게이트가 막혀 변환까지 멈추면 안 된다.
     */
    @PostConstruct
    void load() {
        if (path.isEmpty()) {
            loadClasspath();
            return;
        }
        if (!refreshIfChanged()) {
            log.error("점자 규정을 읽지 못해 검색 결과가 비어 있다: {} — EC2 braille-reference clone 확인", path);
        }
    }

    /**
     * 파일이 바뀌었으면 다시 읽는다(1분 주기). 새 파일이 깨졌으면 기존 데이터를 그대로 쓴다.
     *
     * @return 지금 유효한 데이터가 있으면 true
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public synchronized boolean refreshIfChanged() {
        if (path.isEmpty()) return !snapshot.rules().isEmpty();
        File file = new File(path);
        if (!file.isFile()) {
            if (loadedModified != -2) log.error("점자 규정 파일 없음: {} — 기존 데이터 {}건 유지", path, snapshot.rules().size());
            loadedModified = -2;   // 같은 오류를 1분마다 찍지 않는다
            return !snapshot.rules().isEmpty();
        }
        long modified = file.lastModified();
        long size = file.length();
        if (modified == loadedModified && size == loadedSize) return true;

        int before = snapshot.rules().size();
        try (InputStream is = new FileInputStream(file)) {
            long started = System.nanoTime();
            snapshot = parse(is);
            loadedModified = modified;
            loadedSize = size;
            log.info("점자 규정 {}건 로드 ({}ms, 이전 {}건) — {}", snapshot.rules().size(),
                    (System.nanoTime() - started) / 1_000_000, before, path);
            return true;
        } catch (IOException | RuntimeException e) {
            // 같은 파일로 매분 다시 시도하지 않게 기록은 해 둔다 — 고쳐진 파일이 오면 시각이 바뀐다
            loadedModified = modified;
            loadedSize = size;
            log.error("점자 규정 파싱 실패 — 기존 데이터 {}건 유지: {}", before, path, e);
            return before > 0;
        }
    }

    private void loadClasspath() {
        try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                log.warn("점자 규정 경로(rules.path)가 비어 있고 클래스패스에도 없다 — 검색 결과가 비어 있다");
                return;
            }
            snapshot = parse(is);
            log.info("점자 규정 {}건 로드 (클래스패스)", snapshot.rules().size());
        } catch (IOException e) {
            throw new IllegalStateException("점자 규정 파일 파싱 실패: " + RESOURCE, e);
        }
    }

    static Snapshot parse(InputStream is) throws IOException {
        JsonNode root = new ObjectMapper().readTree(is).get("rules");
        if (root == null || !root.isObject() || root.isEmpty()) {
            throw new IllegalStateException("rules 가 비어 있다");
        }

        // 1) 원문 순서로 정렬 — 파일 키 순서는 문자열 정렬이라 MCST-한글-1.4.10 이 1.4.8 앞에 온다
        List<String> ids = new ArrayList<>();
        root.fieldNames().forEachRemaining(ids::add);
        ids.sort(Comparator.comparing(BrailleRuleRegistry::orderKey, BrailleRuleRegistry::compareOrderKey));

        // 2) 순서가 정해진 뒤에야 displayOrder 를 매긴다
        List<BrailleRule> loaded = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) loaded.add(toRule(ids.get(i), root.get(ids.get(i)), i));

        return new Snapshot(List.copyOf(loaded), loaded.stream().collect(
                Collectors.toUnmodifiableMap(BrailleRule::ruleId, r -> r)));
    }

    private static BrailleRule toRule(String ruleId, JsonNode node, int displayOrder) {
        JsonNode section = node.get("section");
        String part = text(section, "Part");
        String chapter = text(section, "Chapter");
        String sec = text(section, "Section");
        String paragraph = text(section, "Paragraph");

        StringJoiner path = new StringJoiner(" · ");
        for (String key : SECTION_KEYS) {
            String value = text(section, key);
            if (value != null) path.add(value);
        }
        String sectionDisplay = path.toString();

        String ruleName = node.path("rule_name").asText("");
        String contents = node.path("contents").asText("");
        String tag = node.path("default_tag").asText("");
        // 조항 전문(예시 · 붙임 포함, 원문 줄바꿈 그대로) — 없으면 null
        String fullText = node.hasNonNull("full_text") ? node.get("full_text").asText() : null;
        // 점역 미지원 · 검색 전용(음악 · 국제음성기호)이면 false. 칸이 없던 예전 파일은 전부 지원
        boolean supported = node.path("supported").asBoolean(true);

        // 검색은 이 한 칸만 본다 — rule_id·조문 경로·규정명·본문·전문 어디에 걸려도 결과에 포함된다
        String searchBlob = String.join(" ", ruleId, sectionDisplay, ruleName, contents, tag,
                fullText == null ? "" : fullText).toLowerCase(Locale.ROOT);

        return new BrailleRule(
                ruleId, publisherCode(ruleId), node.path("publisher").asText(""),
                node.path("source").asText(""), node.path("version").asInt(),
                part, chapter, sec, paragraph, sectionDisplay,
                ruleName, contents, tag.isBlank() ? null : tag,
                fullText, supported, displayOrder, searchBlob);
    }

    private static String text(JsonNode section, String key) {
        JsonNode value = section == null ? null : section.get(key);
        return value == null ? null : value.asText();
    }

    static String publisherCode(String ruleId) {
        return ruleId.substring(0, ruleId.indexOf('-'));
    }

    /**
     * 원문 순서 키 = [기관 순위, MCST 편 순위, 조문 번호…].
     * rule_id 는 {@code NLD-1.1.1} · {@code NISE-5.3} · {@code MCST-한글-1.4.10} 세 모양뿐이고
     * 마지막 세그먼트는 항상 점으로 이은 숫자다(340건 전수 확인).
     */
    static List<Integer> orderKey(String ruleId) {
        String[] segments = ruleId.split("-");
        List<Integer> key = new ArrayList<>();
        key.add(PUBLISHER_RANK.getOrDefault(segments[0], 99));
        key.add(segments.length == 3 ? MCST_PART_RANK.getOrDefault(segments[1], 99) : 0);
        for (String number : segments[segments.length - 1].split("\\.")) key.add(Integer.parseInt(number));
        return key;
    }

    /** 길이가 다르면 짧은 쪽이 앞 (제1장 < 제1장 제1절) */
    static int compareOrderKey(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int cmp = Integer.compare(a.get(i), b.get(i));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.size(), b.size());
    }

    /** 원문 순서로 정렬된 전체 규정 (불변) */
    public List<BrailleRule> all() {
        return snapshot.rules();
    }

    public Optional<BrailleRule> findById(String ruleId) {
        return Optional.ofNullable(snapshot.byId().get(ruleId));
    }
}
