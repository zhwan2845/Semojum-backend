package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 쪽 결과와 거기 딸린 행(블록 · 규정 · bbox · 품질)을 지운다 — 호출자의 트랜잭션 안에서 돈다.
 *
 * <p>쓰는 곳: 텍스트 작업의 쪽 줄이기({@link TextPageTrimmer}), 실패한 쪽 다시 변환(AI 가 BLOCKED 로 답해
 * 저장된 옛 결과 — 남기면 다시 변환이 성공할 때 같은 쪽에 결과가 두 벌 생긴다).
 * 메모는 쪽({@code pages})에 붙어 있어 여기서 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PageResultCleaner {

    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;

    /** 그 작업의 해당 쪽 번호 결과를 지운다. 지운 결과 수를 돌려준다 */
    public int deleteResultsOf(String jobId, Collection<Integer> pageNos) {
        if (pageNos.isEmpty()) return 0;
        List<UUID> resultIds = pageResultRepository.findByJobIdOrderByPageNumber(jobId).stream()
                .filter(r -> pageNos.contains(r.getPageNumber())).map(PageResult::getId).toList();
        deleteResults(resultIds);
        return resultIds.size();
    }

    public void deleteResults(List<UUID> resultIds) {
        if (resultIds.isEmpty()) return;
        List<UUID> elementIds = new ArrayList<>(textElementRepository.findIdsByPageResultIdIn(resultIds));
        elementIds.addAll(brailleElementRepository.findIdsByPageResultIdIn(resultIds));
        if (!elementIds.isEmpty()) ruleTrailRepository.deleteByElementIdIn(elementIds);
        textElementRepository.deleteByPageResultIdIn(resultIds);
        brailleElementRepository.deleteByPageResultIdIn(resultIds);
        boundingBoxRepository.deleteByPageResultIdIn(resultIds);
        qualityCriticalErrorRepository.deleteByPageResultIdIn(resultIds);
        qualityReviewFlagRepository.deleteByPageResultIdIn(resultIds);
        pageResultRepository.deleteAllById(resultIds);
    }
}
