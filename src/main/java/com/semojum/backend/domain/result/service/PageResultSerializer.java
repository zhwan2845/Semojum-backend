package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;

/**
 * 쪽 결과 → FE 응답 직렬화. SSE(page_done)와 쪽 조회가 함께 쓴다 — 응답 모양을 바꾸면 두 경로가 같이 바뀐다.
 *
 * <p>기준은 <b>AI가 준 변환 결과(proto)</b>다. AI가 채워 보낸 값은 가공 없이 그대로 내보내고,
 * proto에서 폐기된 필드는 내보내지 않는다.
 *
 * <p>proto의 repeated 필드(contents·drafts·flags·rule_trail)는 <b>항상 배열</b>로 내보낸다 —
 * DB에는 빈 값이 null로 저장되지만 FE가 null 체크 없이 순회할 수 있어야 한다.
 */
@Component
@RequiredArgsConstructor
public class PageResultSerializer {

    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;

    /**
     * 모드별 result 구성 — 앱 에디터가 실제로 쓰는 값만 담는다.
     * - a: 이미지→텍스트(예전 작업만 — 새로 만들지 않는다) · 결과물은 text_list
     * - b: 텍스트 작업(TXT · 새 문서) · 결과물은 braille_text_list, text_list는 원문 대조용(같은 id로 1:1)
     * - c: 3패널(PDF · HWP · HWPX · 이미지 업로드 전부) · text_list(전체 필드) + braille_text_list, 같은 id로 1:1.
     *      새 작업의 점자 목록은 비어 있다 — AI가 점자를 주지 않고 앱이 점역한다
     * 이미지 정보(image_resolution·bounding_box_list)는 원본 이미지가 있는 a·c에만 넣는다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildResult(PageResult pageResult) {
        String mode = pageResult.getMode();
        Map<String, Object> result = new LinkedHashMap<>();

        List<TextElement> textElements = textElementRepository.findByPageResult(pageResult);
        List<BrailleElement> brailleElements = brailleElementRepository.findByPageResult(pageResult);
        List<BoundingBox> boundingBoxes = boundingBoxRepository.findByPageResult(pageResult);
        List<QualityCriticalError> criticalErrors = qualityCriticalErrorRepository.findByPageResult(pageResult);
        List<QualityReviewFlag> reviewFlags = qualityReviewFlagRepository.findByPageResult(pageResult);

        boolean hasImage = "a".equals(mode) || "c".equals(mode);
        if (hasImage && pageResult.getImageWidth() != null) {
            Map<String, Object> imgRes = new LinkedHashMap<>();
            imgRes.put("width", pageResult.getImageWidth());
            imgRes.put("height", pageResult.getImageHeight());
            result.put("image_resolution", imgRes);
        }
        if (hasImage) {
            result.put("bounding_box_list", buildBoundingBoxList(boundingBoxes));
        }

        if ("a".equals(mode) || "c".equals(mode)) {
            // 결과물이므로 전체 필드 (c는 텍스트 패널도 편집 대상이다)
            result.put("text_list", buildTextListFull(textElements, loadRuleTrails(ids(textElements, TextElement::getId))));
        } else {
            // b: 좌측 패널에 원문을 띄우고 점자와 나란히 대조한다 — id + contents만
            result.put("text_list", buildTextListSimple(textElements));
        }
        if (!"a".equals(mode)) {
            result.put("braille_text_list",
                    buildBrailleListFull(brailleElements, loadRuleTrails(ids(brailleElements, BrailleElement::getId))));
        }

        result.put("quality_report", buildQualityReport(pageResult, criticalErrors, reviewFlags));
        return result;
    }

    private List<Map<String, Object>> buildTextListFull(List<TextElement> elements,
                                                        Map<UUID, List<Map<String, Object>>> ruleTrails) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TextElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("type", el.getType());
            map.put("order", el.getReadingOrder());
            map.put("heading_level", el.getHeadingLevel());
            map.put("tn_text", el.getTnText());
            map.put("latex_string", el.getLatexString());
            map.put("selected_idx", el.getSelectedIdx());
            map.put("render_mode", el.getRenderMode());
            map.put("visual_subtype", el.getVisualSubtype());
            map.put("contents", orEmpty(el.getCurrentContents()));
            map.put("drafts", orEmpty(el.getDrafts()));
            map.put("is_blocked", el.isBlocked());
            map.put("rule_trail", ruleTrails.getOrDefault(el.getId(), Collections.emptyList()));
            // AI 가 준 요소 추출 신뢰도(0~1). AI 가 0 을 보내면 저장하지 않아 null — 「값 없음」과 「낮음」을 가른다.
            // ⚠️ 실측: 0.95 · 1.00 두 값뿐이다 — 측정값인지 AI 팀 확인 전이다
            map.put("ocr_confidence", round3(el.getOcrConfidence()));
            list.add(map);
        }
        return list;
    }

    /** 원문 대조용 — 점자 요소와 같은 id로 짝지어진다 */
    private List<Map<String, Object>> buildTextListSimple(List<TextElement> elements) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TextElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("contents", orEmpty(el.getCurrentContents()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildBrailleListFull(List<BrailleElement> elements,
                                                           Map<UUID, List<Map<String, Object>>> ruleTrails) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BrailleElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("type", el.getType());
            map.put("order", el.getReadingOrder());
            map.put("heading_level", el.getHeadingLevel());
            map.put("tn_text", el.getTnText());
            map.put("latex_string", el.getLatexString());
            map.put("selected_idx", el.getSelectedIdx());
            map.put("render_mode", el.getRenderMode());
            map.put("visual_subtype", el.getVisualSubtype());
            map.put("contents", orEmpty(el.getCurrentContent()));
            map.put("drafts", orEmpty(el.getDrafts()));
            map.put("is_blocked", el.isBlocked());
            map.put("rule_trail", ruleTrails.getOrDefault(el.getId(), Collections.emptyList()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildBoundingBoxList(List<BoundingBox> boxes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BoundingBox box : boxes) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", box.getElementId());
            map.put("x", box.getX());
            map.put("y", box.getY());
            map.put("x2", box.getX2());
            map.put("y2", box.getY2());
            map.put("type", box.getType());
            map.put("heading_level", box.getHeadingLevel());
            map.put("caption_ref", box.getCaptionRef());
            map.put("flags", orEmpty(box.getFlags()));
            list.add(map);
        }
        return list;
    }

    /**
     * 페이지의 rule_trail을 <b>한 번의 쿼리</b>로 모아 요소 id별로 묶는다.
     *
     * <p>요소마다 따로 조회하면 요소 수만큼 DB를 왕복한다(N+1). 실측: 요소 95개 페이지의 응답이
     * 266ms(행이 캐시에 없는 첫 조회는 1.4s)였고, 그 대부분이 이 왕복이었다.
     * 요소가 없으면 쿼리 자체를 생략한다(빈 IN 절 방지).
     */
    private Map<UUID, List<Map<String, Object>>> loadRuleTrails(Collection<UUID> elementIds) {
        if (elementIds.isEmpty()) return Collections.emptyMap();
        Map<UUID, List<Map<String, Object>>> byElement = new HashMap<>();
        for (RuleTrail rt : ruleTrailRepository.findByElementIdIn(elementIds)) {
            byElement.computeIfAbsent(rt.getElementId(), k -> new ArrayList<>()).add(toRuleTrailMap(rt));
        }
        return byElement;
    }

    private <T> List<UUID> ids(List<T> elements, Function<T, UUID> getId) {
        List<UUID> list = new ArrayList<>(elements.size());
        for (T el : elements) list.add(getId.apply(el));
        return list;
    }

    private Map<String, Object> toRuleTrailMap(RuleTrail rt) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rule_id", rt.getRuleId());
        map.put("source", rt.getSource());
        map.put("priority", rt.getPriority());
        map.put("section", rt.getSection());
        map.put("title", rt.getTitle());
        map.put("excerpt", rt.getExcerpt());
        map.put("line_no", rt.getLineNo());
        map.put("col_start", rt.getColStart());
        map.put("col_end", rt.getColEnd());
        map.put("tag", rt.getTag());
        // 규정 출처 상세 — 어느 기관의 몇 년 판 어느 조문인지. AI가 안 주면 null
        map.put("publisher", rt.getPublisher());
        map.put("version", rt.getVersion());
        map.put("path", rt.getSectionPath());
        return map;
    }

    /**
     * 점역사에게 보여줄 오류·검토 항목과 쪽 평균 추출 신뢰도.
     * 제외: line_overflow_rate(proto에서 폐기)
     */
    private Map<String, Object> buildQualityReport(PageResult pageResult,
                                                   List<QualityCriticalError> criticalErrors,
                                                   List<QualityReviewFlag> reviewFlags) {
        Map<String, Object> report = new LinkedHashMap<>();
        // 쪽 평균 추출 신뢰도(0~1). 품질 보고서가 없던 응답은 null
        report.put("ocr_confidence_avg", round3(pageResult.getOcrConfidenceAvg()));

        List<Map<String, Object>> errors = new ArrayList<>();
        for (QualityCriticalError e : criticalErrors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.getType());
            m.put("element_id", e.getElementId());
            m.put("message", e.getMessage());
            errors.add(m);
        }
        report.put("critical_errors", errors);

        List<Map<String, Object>> flags = new ArrayList<>();
        for (QualityReviewFlag f : reviewFlags) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", f.getType());
            m.put("element_id", f.getElementId());
            m.put("message", f.getMessage());
            flags.add(m);
        }
        report.put("review_flags", flags);

        return report;
    }

    /**
     * 신뢰도를 소수점 셋째 자리로 반올림한다 — AI 가 float 로 보내 0.95 가 0.949999988079071 로 저장된다.
     * 응답에서만 다듬고 DB 값은 그대로 둔다. null 은 null
     */
    static Double round3(Double value) {
        if (value == null) return null;
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? Collections.emptyList() : value;
    }
}
