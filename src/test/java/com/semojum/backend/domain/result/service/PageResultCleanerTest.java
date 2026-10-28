package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 다시 변환 전 옛 결과 지우기 — 고른 쪽의 결과와 딸린 행만 지운다 */
class PageResultCleanerTest {

    PageResultRepository resultRepo = mock(PageResultRepository.class);
    TextElementRepository textRepo = mock(TextElementRepository.class);
    BrailleElementRepository brailleRepo = mock(BrailleElementRepository.class);
    RuleTrailRepository ruleRepo = mock(RuleTrailRepository.class);
    BoundingBoxRepository boxRepo = mock(BoundingBoxRepository.class);
    QualityCriticalErrorRepository criticalRepo = mock(QualityCriticalErrorRepository.class);
    QualityReviewFlagRepository flagRepo = mock(QualityReviewFlagRepository.class);
    PageResultCleaner cleaner = new PageResultCleaner(resultRepo, textRepo, brailleRepo, ruleRepo, boxRepo, criticalRepo, flagRepo);

    private PageResult result(int pageNo) {
        PageResult r = PageResult.builder().pageNumber(pageNo).mode("c").status("BLOCKED").build();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        return r;
    }

    @Test
    void 고른_쪽의_결과만_딸린_행과_함께_지운다() {
        PageResult r1 = result(1), r2 = result(2), r3 = result(3);
        when(resultRepo.findByJobIdOrderByPageNumber("job")).thenReturn(List.of(r1, r2, r3));
        UUID el = UUID.randomUUID();
        when(textRepo.findIdsByPageResultIdIn(any())).thenReturn(List.of(el));
        when(brailleRepo.findIdsByPageResultIdIn(any())).thenReturn(List.of());

        assertEquals(1, cleaner.deleteResultsOf("job", List.of(2)));

        List<UUID> only2 = List.of(r2.getId());
        verify(ruleRepo).deleteByElementIdIn(List.of(el));
        verify(textRepo).deleteByPageResultIdIn(only2);
        verify(brailleRepo).deleteByPageResultIdIn(only2);
        verify(boxRepo).deleteByPageResultIdIn(only2);
        verify(criticalRepo).deleteByPageResultIdIn(only2);
        verify(flagRepo).deleteByPageResultIdIn(only2);
        verify(resultRepo).deleteAllById(only2);
    }

    @Test
    void 결과가_없는_쪽이면_아무것도_지우지_않는다() {
        when(resultRepo.findByJobIdOrderByPageNumber("job")).thenReturn(List.of(result(1)));

        assertEquals(0, cleaner.deleteResultsOf("job", List.of(2)));

        verify(resultRepo, never()).deleteAllById(any());
        verify(textRepo, never()).deleteByPageResultIdIn(any());
    }
}
