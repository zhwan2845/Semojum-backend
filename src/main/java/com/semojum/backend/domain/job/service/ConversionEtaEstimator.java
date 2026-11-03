package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.scheduler.CreditGate;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.global.grpc.AiServerPool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 변환 잔여 시간 — 변환 진행 화면의 「잔여 1시간 12분」(피그마 · 기획 0 「128쪽 중 47쪽 · 잔여 약 2분」).
 *
 * <p>다운로드 잔여 시간과 같은 방식이다 — 처음부터 맞히려 하지 않고, 실제로 처리되는 속도를 재서 남은 양을 나눈다.
 * <ul>
 *   <li><b>쪽이 3개 이상 끝났으면 실제 속도</b> — 이 작업의 최근 결과 10개가 나온 간격으로 쪽/초를 잰다.
 *       다른 사용자와 슬롯을 나눠 쓰는 것 · 그림 많은 문서가 느린 것이 저절로 들어간다</li>
 *   <li><b>그 전엔 과거 기록</b> — 최근 200쪽의 평균 처리 시간 × 남은 쪽 ÷ 이 작업이 쓸 수 있는 슬롯 수.
 *       평균을 쓴다 — 남은 시간은 쪽 시간의 합이라 중앙값(6초)으로 세면 오래 걸리는 쪽(평균 30초, 2026-11 실측)을 빼먹는다</li>
 *   <li><b>크레딧이 10 미만이면 한 쪽씩</b> 나가므로 슬롯을 1로 본다(기획 10 「직렬 처리 중에는 잔여 시간이 늘어난다」)</li>
 *   <li><b>값이 튀지 않게</b> 직전 값(그동안 흐른 시간을 뺀)과 섞는다(지수 평활)</li>
 * </ul>
 *
 * <p>쪽 「안」의 진행은 모른다 — AI가 처리 중 신호를 주지 않는다. 그래서 1쪽짜리는 끝날 때까지 첫 추정이 줄어들기만 한다.
 * 기억은 메모리에 둔다(BE 단일 인스턴스 전제, 재시작하면 다시 센다).
 */
@Component
@RequiredArgsConstructor
public class ConversionEtaEstimator {

    /** 실제 속도로 바꾸는 최소 완료 쪽 수 — 그보다 적으면 간격 하나가 전체를 흔든다 */
    static final int MIN_SAMPLES = 3;
    /** 새 추정의 비중 — 나머지는 직전 값(흐른 시간만큼 줄인)이다 */
    static final double SMOOTHING = 0.3;
    /** 처리 기록이 하나도 없을 때의 쪽당 시간(초) */
    static final double DEFAULT_PAGE_SECONDS = 30;
    private static final long CACHE_MILLIS = 5_000;
    private static final long AVERAGE_CACHE_MILLIS = 300_000;

    private final PageResultRepository pageResultRepository;
    private final JobRepository jobRepository;
    private final AiServerPool aiServerPool;
    private final CreditGate creditGate;

    private record Estimate(double seconds, long at) {}

    private final Map<String, Estimate> last = new ConcurrentHashMap<>();
    private volatile Estimate averagePage;   // seconds = 최근 쪽당 평균
    private volatile Estimate activeJobs;    // seconds = 변환 중 작업 수

    /**
     * 남은 시간(초, 올림). 남은 쪽이 없으면 0.
     *
     * @param remainingPages 아직 결과가 안 나온 쪽(대기 + 처리 중)
     */
    public int remainingSeconds(String jobId, int remainingPages) {
        if (remainingPages <= 0) {
            last.remove(jobId);
            return 0;
        }
        long now = System.currentTimeMillis();
        Estimate prev = last.get(jobId);
        // SSE가 1초마다 묻는다 — 5초 안이면 DB를 다시 읽지 않고 직전 값에서 흐른 시간만 뺀다
        if (prev != null && now - prev.at() < CACHE_MILLIS) {
            return (int) Math.ceil(Math.max(0, prev.seconds() - (now - prev.at()) / 1000.0));
        }
        double raw = observedSeconds(jobId, remainingPages, now);
        if (Double.isNaN(raw)) raw = coldStartSeconds(jobId, remainingPages, now);

        double smoothed = prev == null ? raw
                : SMOOTHING * raw + (1 - SMOOTHING) * Math.max(0, prev.seconds() - (now - prev.at()) / 1000.0);
        last.put(jobId, new Estimate(smoothed, now));
        if (last.size() > 10_000) last.clear();   // 끝난 작업이 쌓이지 않게
        return (int) Math.ceil(smoothed);
    }

    /** 이 작업의 최근 결과 간격으로 잰 남은 시간 — 결과가 3개 미만이면 NaN */
    double observedSeconds(String jobId, int remainingPages, long now) {
        List<Timestamp> times = pageResultRepository.findRecentResultTimes(jobId);
        if (times.size() < MIN_SAMPLES) return Double.NaN;
        // 가장 오래된 결과부터 지금까지 n쪽이 나왔다 — 마지막 결과 뒤 멈춘 시간도 속도에 들어간다
        double span = (now - times.get(times.size() - 1).getTime()) / 1000.0;
        if (span <= 0) return Double.NaN;
        double pagesPerSecond = (times.size() - 1) / span;
        return pagesPerSecond <= 0 ? Double.NaN : remainingPages / pagesPerSecond;
    }

    /** 첫 추정 — 최근 평균 쪽 시간 × 남은 쪽 ÷ 이 작업이 쓸 수 있는 슬롯 수 */
    double coldStartSeconds(String jobId, int remainingPages, long now) {
        double perPage = averagePageSeconds(now);
        int slots = Math.max(1, aiServerPool.getTotalSlots());
        double share = creditGate.serial(jobId) ? 1 : Math.max(1.0, slots / Math.max(1.0, activeJobCount(now)));
        double parallel = Math.min(share, remainingPages);
        return Math.ceil(remainingPages / parallel) * perPage;
    }

    private double averagePageSeconds(long now) {
        Estimate cached = averagePage;
        if (cached == null || now - cached.at() > AVERAGE_CACHE_MILLIS) {
            Double ms = pageResultRepository.averageRecentProcessingMs();
            cached = new Estimate(ms == null || ms <= 0 ? DEFAULT_PAGE_SECONDS : ms / 1000.0, now);
            averagePage = cached;
        }
        return cached.seconds();
    }

    private double activeJobCount(long now) {
        Estimate cached = activeJobs;
        if (cached == null || now - cached.at() > CACHE_MILLIS) {
            cached = new Estimate(jobRepository.countByStatus("IN_PROGRESS"), now);
            activeJobs = cached;
        }
        return cached.seconds();
    }
}
