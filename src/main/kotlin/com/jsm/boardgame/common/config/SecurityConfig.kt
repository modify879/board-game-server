package com.jsm.boardgame.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
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
 * `/api/admin` 이하 전부는 ADMIN 역할을 추가로 요구한다.
 *
 * 액세스 토큰 검증은 [JwtDecoder] 빈(`user.infrastructure.security.JwtDecoderConfig`)에
 * 위임한다. 그 디코더가 로그아웃/세션 교체로 무효화된 토큰을 블랙리스트 검증기로 걸러낸다.
 * 401/403 은 필터 단계에서 응답이 끝나 `GlobalExceptionHandler` 를 거치지 않으므로 커스텀
 * `AuthenticationEntryPoint`/`AccessDeniedHandler`(`common.support`)로 교체한다 (규칙 8).
 * `oauth2ResourceServer { }` 안에도 등록해야 한다 — `exceptionHandling` 에만 두면 Bearer 헤더가
 * 실려 온 요청이 기본 `BearerTokenAuthenticationEntryPoint` 에 먼저 매칭돼 건너뛴다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        jwtAuthenticationConverter: Converter<Jwt, out AbstractAuthenticationToken>,
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
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it
                    .jwt { jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter) }
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }
            .exceptionHandling {
                it
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }
            .build()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
