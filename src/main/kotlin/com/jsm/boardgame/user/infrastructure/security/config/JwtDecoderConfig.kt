package com.jsm.boardgame.user.infrastructure.security.config

import com.jsm.boardgame.user.infrastructure.security.adapter.JwtBlacklistValidator
import com.jsm.boardgame.user.application.port.AuthSessionStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import javax.crypto.spec.SecretKeySpec

/**
 * 리소스 서버(Authorization 헤더의 액세스 토큰 검증)가 쓰는 [JwtDecoder].
 * [JwtBlacklistValidator] 를 물려, 로그아웃/세션 교체로 무효화된 액세스 토큰이
 * 서명이 멀쩡해도 걸러지게 한다.
 */
@Configuration
class JwtDecoderConfig(
    private val properties: JwtProperties,
    private val sessions: AuthSessionStore,
) {

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val secretKey = SecretKeySpec(properties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

        val decoder = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

        // JwtValidators.createDefault() 가 exp/nbf 를 검증한다(NimbusJwtDecoder 의 기본값과 동일).
        // 여기에 블랙리스트 검증기를 더해 표준 파이프라인 하나로 합친다.
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefault(),
                JwtBlacklistValidator(sessions),
            ),
        )

        return decoder
    }
}
