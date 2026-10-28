package com.semojum.backend.global.grpc;

import com.semojum.backend.grpc.BrailleRequest;
import com.semojum.backend.grpc.BrailleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
/** AI 서버(gRPC) 호출 — 풀에서 슬롯을 빌려 {@code ProcessPage}를 부르고 반납한다. 워커 하나가 슬롯 하나를 점유한다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrailleGrpcClient {

    private final AiServerPool pool;

    /**
     * 풀에서 슬롯을 확보한 서버로 변환 요청을 보낸다.
     * deadline({@code grpc.ai.deadline-seconds}, 지금 400s)은 AI 서버 하드 타임아웃(180s) × 최악(대기 + 변환)보다 높게 —
     * 응답 없는 요청이 슬롯을 영구 점유하는 것을 막는다(deadline 초과 시 예외 → 슬롯 반납 → 재시도 로직으로).
     */
    public BrailleResponse processPage(BrailleRequest request) {
        AiServerPool.AiServer server;
        try {
            server = pool.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 서버 슬롯 대기 중 인터럽트", e);
        }
        try {
            return server.getStub()
                    .withDeadlineAfter(pool.getDeadlineSeconds(), TimeUnit.SECONDS)
                    .processPage(request);
        } finally {
            pool.release(server);
        }
    }

    // RPC는 ProcessPage 하나뿐이다 — AI는 교열한 묵자만 돌려주고 점역은 앱이 한다
}
