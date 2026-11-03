package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * HWP → PDF 변환 도구의 동시 실행을 메모리 예산으로 막는다.
 *
 * <p>변환 도구(rhwp · pyhwp · LibreOffice)는 JVM 밖 프로세스라 {@code -Xmx}가 걸리지 않는다. 업로드가 몰리면
 * 서버 메모리를 넘겨 OOM killer가 가장 큰 프로세스인 백엔드 JVM을 죽일 수 있다. 개수가 아니라 예상 메모리로 막아
 * 작은 파일 여럿은 함께 돌리고 큰 파일 조합만 기다리게 한다.
 *
 * <p>공정 모드(먼저 온 순서)라 작은 파일이 계속 끼어들어 큰 파일이 굶지 않는다. 예산보다 큰 예상치는 예산 전체로
 * 줄여 혼자 돌게 한다. ⚠️ 단일 인스턴스 전제 — 서버마다 예산이 따로다.
 */
@Slf4j
@Component
public class ConversionMemoryGate {

    /** 변환 단계 — 단계마다 예상 메모리가 10배쯤 다르다(rhwp 수십 MB · LibreOffice 수백 MB) */
    public enum Stage { RHWP, FALLBACK }

    private final ConversionMemoryProperties properties;
    private final Semaphore budget;

    public ConversionMemoryGate(ConversionMemoryProperties properties) {
        this.properties = properties;
        this.budget = new Semaphore((int) properties.getBudgetMb(), true);
    }

    /** 예산을 쓰는 동안 들고 있다가 닫으면 돌려준다(try-with-resources) */
    public final class Permit implements AutoCloseable {
        private final int weightMb;
        private boolean released;

        private Permit(int weightMb) {
            this.weightMb = weightMb;
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            budget.release(weightMb);
        }
    }

    /**
     * 그 단계의 예상 메모리만큼 예산을 잡는다. 빌 때까지 기다리고, 상한을 넘기면 JOB4017.
     */
    public Permit acquire(Stage stage, long fileBytes) {
        int weightMb = weightMb(stage, fileBytes);
        long started = System.nanoTime();
        boolean acquired;
        try {
            acquired = budget.tryAcquire(weightMb, properties.getWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.JOB_CONVERT_BUSY);
        }
        long waitedMs = (System.nanoTime() - started) / 1_000_000;
        if (!acquired) {
            log.warn("변환 예산 대기 초과: stage={}, weightMb={}, waitedMs={}, availableMb={}",
                    stage, weightMb, waitedMs, budget.availablePermits());
            throw new CustomException(ErrorCode.JOB_CONVERT_BUSY);
        }
        if (waitedMs >= 1000) {
            log.info("변환 예산 대기 후 시작: stage={}, weightMb={}, waitedMs={}", stage, weightMb, waitedMs);
        }
        return new Permit(weightMb);
    }

    int weightMb(Stage stage, long fileBytes) {
        long fileMb = (fileBytes + (1 << 20) - 1) >> 20;   // 올림
        long weight = stage == Stage.RHWP
                ? properties.getRhwpBaseMb() + properties.getRhwpPerFileMb() * fileMb
                : properties.getFallbackBaseMb() + properties.getFallbackPerFileMb() * fileMb;
        return (int) Math.min(weight, properties.getBudgetMb());
    }

    int availableMb() {
        return budget.availablePermits();
    }
}
