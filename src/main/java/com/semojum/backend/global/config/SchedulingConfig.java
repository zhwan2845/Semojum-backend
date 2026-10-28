package com.semojum.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @Scheduled}를 켠다 — 메인 클래스에 {@code @EnableScheduling}을 두지 않아 별도 설정으로 활성화한다 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
