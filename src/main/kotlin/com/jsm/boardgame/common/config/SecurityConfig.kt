package com.jsm.boardgame.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * 인증은 JWT 를 Authorization 헤더로 받는 무상태 방식이다.
 * 세션을 쓰지 않으므로 STATELESS 로 잡는다.
 *
 * CSRF 를 끄는 것은 임시방편이 아니다. Bearer 헤더는 브라우저가 자동으로 실어 보내지 않으므로
 * CSRF 가 원리적으로 성립하지 않는다.
 *
 * 인가 규칙은 회원가입/로그인/토큰 갱신만 permitAll 이고 그 외 전부 인증을 요구한다
 * (`GET /api/users/{id}`, `POST /api/auth/logout` 포함 — 확정된 결정).
 *
 * 액세스 토큰 검증은 [JwtDecoder] 빈(`user.infrastructure.security.JwtDecoderConfig`)에
 * 위임한다. 그 디코더가 로그아웃/세션 교체로 무효화된 토큰을 블랙리스트 검증기로 걸러낸다.
 * 401/403 은 스프링 시큐리티가 필터 단계에서 직접 응답을 끝내 `GlobalExceptionHandler`
 * 를 거치지 않으므로, 같은 오류 계약을 내도록 커스텀 `AuthenticationEntryPoint`/
 * `AccessDeniedHandler`(`common.support`)로 교체한다.
 *
 * 이 교체는 `oauth2ResourceServer { }` DSL 안에서도 명시해야 한다 — `exceptionHandling` 에만
 * 등록하면, Authorization: Bearer 헤더가 실려 온(있지만 검증에 실패한) 요청은 스프링 시큐리티가
 * `defaultAuthenticationEntryPointFor` 로 등록한 기본 `BearerTokenAuthenticationEntryPoint` 를
 * 우선 매칭시켜 커스텀 엔트리포인트를 건너뛴다(본문 없는 401 + WWW-Authenticate 헤더만 응답).
 * 헤더 자체가 없는 요청만 `exceptionHandling` 쪽 기본값을 타므로 둘 다 등록해야 모든 401 이
 * 같은 오류 계약을 낸다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        authenticationEntryPoint: AuthenticationEntryPoint,
        accessDeniedHandler: AccessDeniedHandler,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it
                    .jwt { jwt -> jwt.decoder(jwtDecoder) }
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }
            .exceptionHandling {
                it
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }
            .build()

    /**
     * 도메인의 `PasswordHasher` 포트 구현체가 주입받는다.
     * 스프링 시큐리티 타입이 `infrastructure` 밖으로 나가지 않게 하는 경계다.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
