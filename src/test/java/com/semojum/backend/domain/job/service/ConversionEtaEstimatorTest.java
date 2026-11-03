package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.scheduler.CreditGate;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.global.grpc.AiServerPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 변환 잔여 시간 — 처음엔 과거 평균으로, 쪽이 끝나기 시작하면 이 작업의 실제 속도로 */
class ConversionEtaEstimatorTest {

    PageResultRepository results;
    JobRepository jobs;
    CreditGate gate;
    ConversionEtaEstimator estimator;

    @BeforeEach
    void setUp() {
        results = mock(PageResultRepository.class);
        jobs = mock(JobRepository.class);
        gate = mock(CreditGate.class);
        AiServerPool pool = mock(AiServerPool.class);
        when(pool.getTotalSlots()).thenReturn(4);
        estimator = new ConversionEtaEstimator(results, jobs, pool, gate);
        when(results.findRecentResultTimes(anyString())).thenReturn(List.of());
        when(results.averageRecentProcessingMs()).thenReturn(30_000.0);
        when(jobs.countByStatus("IN_PROGRESS")).thenReturn(1L);
    }

    /** 혼자 변환 중이면 슬롯 4개를 다 쓴다 — 8쪽 × 평균 30초 ÷ 4 = 60초 */
    @Test
    void 처음엔_과거_평균과_슬롯_수로_센다() {
        assertEquals(60, estimator.remainingSeconds("job", 8));
    }

    /** 두 작업이 나눠 쓰면 슬롯 2개 — 120초 */
    @Test
    void 다른_작업과_슬롯을_나눠_쓰면_길어진다() {
        when(jobs.countByStatus("IN_PROGRESS")).thenReturn(2L);
        assertEquals(120, estimator.remainingSeconds("job", 8));
    }

    /** 크레딧 10 미만이면 한 쪽씩 — 8쪽 × 30초 */
    @Test
    void 한_쪽씩_나가면_슬롯을_1로_본다() {
        when(gate.serial("job")).thenReturn(true);
        assertEquals(240, estimator.remainingSeconds("job", 8));
    }

    /** 쪽이 3개 이상 끝나면 실제 속도 — 최근 10쪽이 90초에 걸쳐 나왔으면 10초에 1쪽, 남은 6쪽은 60초 */
    @Test
    void 쪽이_끝나기_시작하면_실제_속도로_센다() {
        long now = System.currentTimeMillis();
        List<Timestamp> times = IntStream.range(0, 10).mapToObj(i -> new Timestamp(now - i * 10_000L)).toList();
        when(results.findRecentResultTimes("job")).thenReturn(times);

        int sec = estimator.remainingSeconds("job", 6);
        assertTrue(sec >= 59 && sec <= 61, "남은 6쪽 × 10초 = 약 60초, 실제 " + sec);
    }

    @Test
    void 남은_쪽이_없으면_0이다() {
        assertEquals(0, estimator.remainingSeconds("job", 0));
    }

    /** 처리 기록이 하나도 없으면 쪽당 30초로 본다 */
    @Test
    void 기록이_없으면_쪽당_30초() {
        when(results.averageRecentProcessingMs()).thenReturn(null);
        assertEquals(30, estimator.remainingSeconds("job", 1));
    }

    /** 1초마다 물어도 5초 안엔 DB를 다시 읽지 않는다 */
    @Test
    void 오초_안에는_다시_읽지_않는다() {
        estimator.remainingSeconds("job", 8);
        estimator.remainingSeconds("job", 8);
        verify(results, times(1)).findRecentResultTimes("job");
    }
}
