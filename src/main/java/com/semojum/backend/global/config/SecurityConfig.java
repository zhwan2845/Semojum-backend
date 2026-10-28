package com.semojum.backend.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.jwt.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
/**
 * 인증 · 인가 · CORS.
 *
 * <p>⚠️ CORS는 전면 허용({@code allowedOriginPatterns *}) 유지 — Bearer라 origin 제한이 무의미하고, 좁히면 데스크톱 앱(Origin: null)이 403으로 깨진다(회귀 실측)
 *
 * <p>⚠️ {@code /error}는 permitAll 유지 — 빼면 예외 1건이 인가 거부 스택 수백 줄로 불어난다
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    // CORS origin 패턴(쉼표 구분). 기본 * — 이유는 corsConfigurationSource 주석
    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // refresh/logout은 refreshToken으로 자체 검증하므로 액세스 토큰을 요구하지 않는다
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // 운영자 API — JWT(ROLE_ADMIN) 전용
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 헬스체크 — Envoy·배포 스크립트가 무인증으로 호출
                        .requestMatchers("/api/health").permitAll()
                        // 홈페이지 공개 접수 — 무인증 (남용 방어는 서비스 계층)
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 에러 디스패치(/error 포워드)까지 인가를 요구하면 예외 1건이
                        // AuthorizationDeniedException 스택 수백 줄로 증폭된다(실측) — 반드시 허용
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            // 미인증 접근은 일상(봇·만료 토큰) — 스택 없이 WARN 한 줄만
                            log.warn("인증 거부: {} {} → 401", request.getMethod(), request.getRequestURI());
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            new ObjectMapper().writeValue(
                                    response.getWriter(),
                                    ApiResponse.failure(ErrorCode.COMMON_UNAUTHORIZED)
                            );
                        })
                        // 인증은 됐지만 역할이 부족한 경우(예: 점역사 토큰으로 /api/admin/**) — 403 JSON
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            log.warn("인가 거부: {} {} → 403", request.getMethod(), request.getRequestURI());
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            new ObjectMapper().writeValue(
                                    response.getWriter(),
                                    ApiResponse.failure(ErrorCode.COMMON_FORBIDDEN)
                            );
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 운영자 콘솔(브라우저) fetch용 CORS — 기본 전면 허용.
    // - JWT는 Authorization 헤더로 실리고 쿠키를 안 써서(allowCredentials 없음) origin 제한이 보안상 의미가 없다
    // - ⚠️ 허용 목록으로 좁히면 데스크톱 앱(Origin: null)의 로그인이 "Invalid CORS request" 403으로 깨진다(회귀 실측).
    //   좁힐 일이 생기면 앱 origin까지 넣을 것
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Client-Os: 앱 접속 환경 — 브라우저 preflight 통과용
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Client-Os"));
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}