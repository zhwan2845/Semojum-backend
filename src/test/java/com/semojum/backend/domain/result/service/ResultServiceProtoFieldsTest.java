package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.billing.service.CreditDeductionService;
import com.semojum.backend.domain.billing.service.UsageCostService;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.RuleTrail;
import com.semojum.backend.domain.result.entity.TextElement;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.grpc.BrailleResponse;
import com.semojum.backend.grpc.Draft;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** proto 협의판(2026-11-01)의 새 칸을 버리지 않고 저장하는지 — review_flag · Draft.option · RuleTrail.axis · 표 칸 글 */
class ResultServiceProtoFieldsTest {

    @Test
    @SuppressWarnings("unchecked")
    void 새_칸을_저장한다() {
        JobRepository jobs = mock(JobRepository.class);
        PageRepository pages = mock(PageRepository.class);
        TextElementRepository texts = mock(TextElementRepository.class);
        RuleTrailRepository rules = mock(RuleTrailRepository.class);
        Job job = Job.builder().id("job_1").mode("c").totalPages(1).originalFileName("a.pdf").build();
        when(jobs.findById("job_1")).thenReturn(Optional.of(job));
        when(pages.findByJobAndPageNo(job, 1)).thenReturn(Optional.of(Page.builder().job(job).pageNo(1).pdfPath("p").build()));
        when(pages.findByJob(any())).thenReturn(List.of());
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));

        ResultService service = new ResultService(jobs, pages, mock(PageResultRepository.class), texts,
                mock(BoundingBoxRepository.class), rules, mock(QualityCriticalErrorRepository.class),
                mock(QualityReviewFlagRepository.class), redis, mock(UsageCostService.class),
                mock(CreditTransactionRepository.class), mock(CreditDeductionService.class));

        BrailleResponse response = BrailleResponse.newBuilder().setJobId("job_1").setPageNumber(1).setStatus("COMPLETED")
                .addTextList(com.semojum.backend.grpc.TextElement.newBuilder().setId("el-1").setType("figure")
                        .addContents("그림").setReviewFlag("open")
                        .addDrafts(Draft.newBuilder().setText("짧게").setLabel("기본").setOption(2))
                        .addDrafts(Draft.newBuilder().setText("옛 AI").setLabel("줄글"))   // option을 안 준 옛 응답
                        .addRuleTrail(com.semojum.backend.grpc.RuleTrail.newBuilder().setRuleId("r1").setSection("s")
                                .setColStart(0).setColEnd(2).setAxis("print")))
                .addTextList(com.semojum.backend.grpc.TextElement.newBuilder().setId("el-2").setType("table")
                        .addContents("표").setTableSource("<!표><!행><!칸>가").setTableBoxLevel(1))
                .build();
        try {
            service.save(response);
        } catch (RuntimeException ignored) {
            // 종료 판정 등 뒤쪽 단계는 이 테스트의 관심이 아니다 — 요소 · 규정 저장은 그 전에 끝난다
        }

        ArgumentCaptor<TextElement> el = ArgumentCaptor.forClass(TextElement.class);
        verify(texts, times(2)).save(el.capture());
        TextElement figure = el.getAllValues().get(0);
        assertEquals("open", figure.getReviewFlag());
        assertEquals(2, figure.getDrafts().get(0).get("option"));
        assertNull(figure.getDrafts().get(1).get("option"), "option 0(안 줌)은 null");
        assertNull(figure.getTableSource());
        TextElement table = el.getAllValues().get(1);
        assertNull(table.getReviewFlag(), "빈 신호는 null");
        assertEquals("<!표><!행><!칸>가", table.getTableSource());
        assertEquals(1, table.getTableBoxLevel());

        ArgumentCaptor<RuleTrail> rt = ArgumentCaptor.forClass(RuleTrail.class);
        verify(rules).save(rt.capture());
        assertEquals("print", rt.getValue().getAxis());
    }
}
