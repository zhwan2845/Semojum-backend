package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.hwp.ConversionMemoryGate.Permit;
import com.semojum.backend.global.hwp.ConversionMemoryGate.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 변환 메모리 예산 — 개수가 아니라 예상 메모리로 들여보낸다 */
class ConversionMemoryGateTest {

    private static final long MB = 1L << 20;

    private ConversionMemoryGate gate(long budgetMb, long waitSeconds) {
        ConversionMemoryProperties p = new ConversionMemoryProperties();
        p.setBudgetMb(budgetMb);
        p.setWaitSeconds(waitSeconds);
        return new ConversionMemoryGate(p);
    }

    @Test
    void 예상치는_단계마다_기본에_파일_크기_비례분을_더하고_예산을_넘지_않는다() {
        ConversionMemoryGate gate = gate(1200, 1);
        assertEquals(50 + 12, gate.weightMb(Stage.RHWP, 5 * 1024));          // 5KB → 1MB로 올림
        assertEquals(300 + 6, gate.weightMb(Stage.FALLBACK, 5 * 1024));
        assertEquals(50 + 12 * 28, gate.weightMb(Stage.RHWP, 28 * MB));       // 실측 316MB
        assertEquals(300 + 6 * 28, gate.weightMb(Stage.FALLBACK, 28 * MB));   // 실측 421~442MB
        assertEquals(1200, gate.weightMb(Stage.FALLBACK, 500 * MB));          // 예산보다 크면 예산 전체 — 혼자 돈다
    }

    /** 작은 파일은 개수 제한 없이 예산 안에서 함께 돈다 — 2개 고정이면 과하게 막는다 */
    @Test
    void 작은_파일_여럿은_예산_안에서_함께_돌고_넘치면_기다리다_JOB4017() {
        ConversionMemoryGate gate = gate(1200, 1);
        List<Permit> held = new ArrayList<>();
        for (int i = 0; i < 7; i++) held.add(gate.acquire(Stage.RHWP, 10 * MB));   // 10MB rhwp 170MB × 7 = 1190
        assertEquals(10, gate.availableMb());
        assertThrows(CustomException.class, () -> gate.acquire(Stage.RHWP, 10 * MB));
        held.forEach(Permit::close);

        Permit a = gate.acquire(Stage.FALLBACK, 10 * MB);   // 360
        Permit b = gate.acquire(Stage.FALLBACK, 10 * MB);   // 360
        Permit c = gate.acquire(Stage.FALLBACK, 10 * MB);   // 360 → 남은 120
        CustomException e = assertThrows(CustomException.class, () -> gate.acquire(Stage.FALLBACK, 10 * MB));
        assertEquals(ErrorCode.JOB_CONVERT_BUSY, e.getErrorCode());
        a.close();
        b.close();
        c.close();
        assertEquals(1200, gate.availableMb());
    }

    @Test
    void 자리가_나면_기다리던_변환이_들어간다() throws Exception {
        ConversionMemoryGate gate = gate(1000, 10);
        Permit big = gate.acquire(Stage.FALLBACK, 100 * MB);   // 900
        CompletableFuture<Permit> waiting = CompletableFuture.supplyAsync(() -> gate.acquire(Stage.FALLBACK, 1));
        Thread.sleep(300);
        assertFalse(waiting.isDone());
        big.close();
        Permit got = waiting.get(5, TimeUnit.SECONDS);
        got.close();
        assertEquals(1000, gate.availableMb());
    }

    /** 먼저 온 큰 파일 뒤에 온 작은 파일은 자리가 있어도 기다린다 — 작은 파일이 계속 끼어들면 큰 파일이 굶는다 */
    @Test
    void 먼저_기다리던_큰_파일보다_작은_파일이_새치기하지_않는다() throws Exception {
        ConversionMemoryGate gate = gate(1000, 10);
        Permit running = gate.acquire(Stage.FALLBACK, 50 * MB);   // 600 → 남은 400
        CompletableFuture<Permit> large = CompletableFuture.supplyAsync(() -> gate.acquire(Stage.FALLBACK, 50 * MB));
        Thread.sleep(300);
        CompletableFuture<Permit> small = CompletableFuture.supplyAsync(() -> gate.acquire(Stage.RHWP, 1));   // 62
        Thread.sleep(300);
        assertFalse(small.isDone(), "남은 400MB에 들어가지만 먼저 온 큰 파일 뒤에 서야 한다");

        running.close();
        Permit l = large.get(5, TimeUnit.SECONDS);
        Permit s = small.get(5, TimeUnit.SECONDS);
        l.close();
        s.close();
        assertEquals(1000, gate.availableMb());
    }

    @Test
    void 두_번_닫아도_한_번만_돌려준다() {
        ConversionMemoryGate gate = gate(1000, 1);
        Permit p = gate.acquire(Stage.RHWP, 1);
        p.close();
        p.close();
        assertEquals(1000, gate.availableMb());
    }

    @Test
    void 기다리는_스레드가_끊기면_인터럽트_표시를_되살리고_JOB4017() {
        ConversionMemoryGate gate = gate(100, 10);
        Permit all = gate.acquire(Stage.FALLBACK, 1);   // 예산 전체
        Thread.currentThread().interrupt();
        CustomException e = assertThrows(CustomException.class, () -> gate.acquire(Stage.RHWP, 1));
        assertEquals(ErrorCode.JOB_CONVERT_BUSY, e.getErrorCode());
        assertTrue(Thread.interrupted());   // 표시 확인 겸 지운다
        all.close();
    }
}
