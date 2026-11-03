package com.semojum.backend.global.hwp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HWP → PDF 동시 변환의 메모리 예산 (application.yaml: {@code hwp2pdf.memory.*}).
 *
 * <p>단계마다 예상 메모리 = 기본 + 파일 MB당 증가분. 운영 서버 실측(CPU 1 · 컨테이너 최대 메모리)으로 정했다.
 * <ul>
 *   <li>rhwp: 작은 파일 5~27MB, 28MB 2단 논문 316MB → 50 + 12/MB</li>
 *   <li>기존 변환기(pyhwp · LibreOffice): 작은 파일 74~284MB, 28MB 논문 421~442MB → 300 + 6/MB</li>
 *   <li>예산 1200MB: 서버 3.8GB에서 JVM 둘(블루그린 전환 순간) · Redis · Envoy · OS 몫을 뺀 값</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hwp2pdf.memory")
public class ConversionMemoryProperties {

    private long budgetMb = 1200;

    // 예산이 빌 때까지 기다리는 상한 — 넘기면 JOB4017(잠시 후 다시 시도)
    private long waitSeconds = 60;

    private long rhwpBaseMb = 50;
    private long rhwpPerFileMb = 12;

    private long fallbackBaseMb = 300;
    private long fallbackPerFileMb = 6;
}
