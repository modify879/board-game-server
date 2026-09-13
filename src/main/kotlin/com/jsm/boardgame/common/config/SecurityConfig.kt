package com.jsm.boardgame.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

/**
 * 인증은 JWT 를 Authorization 헤더로 받는 무상태 방식이다(`feature/user-auth` 에서 구현).
 * 세션을 쓰지 않으므로 지금부터 STATELESS 로 잡는다 — 나중에 엎지 않기 위해서다.
 *
 * CSRF 를 끄는 것은 임시방편이 아니다. Bearer 헤더는 브라우저가 자동으로 실어 보내지 않으므로
 * CSRF 가 원리적으로 성립하지 않는다.
 *
 * 임시인 것은 **인가 규칙 하나뿐**이다. 로그인이 아직 없어 모든 요청을 허용하고 있으며,
 * 인증 기능이 들어오면 여기만 조인다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() } // TODO(feature/user-auth): 인가 규칙 적용
            .build()

    /**
     * 도메인의 `PasswordHasher` 포트 구현체가 주입받는다.
     * 스프링 시큐리티 타입이 `infrastructure` 밖으로 나가지 않게 하는 경계다.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
