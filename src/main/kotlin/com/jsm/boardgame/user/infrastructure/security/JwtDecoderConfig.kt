package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.application.port.AuthSessionStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import javax.crypto.spec.SecretKeySpec

/**
 * 리소스 서버(Authorization 헤더의 액세스 토큰 검증)가 쓰는 [JwtDecoder] 를 별도 빈으로 뗀다.
 *
 * `JwtTokenIssuer` 도 내부에 자기 디코더를 갖고 있지만 그건 리프레시 토큰 검증 전용이고,
 * `JwtTokenIssuer(properties: JwtProperties)` 생성자 하나로 스프링 없이 직접 테스트된다
 * (`JwtTokenIssuerTest` 8개). 그 생성자에 이 디코더를 주입받게 바꾸면 그 테스트들이 깨지므로
 * 건드리지 않는다. 대신 같은 비밀키로 별도 인스턴스를 여기서 만들고, 여기에만
 * [JwtBlacklistValidator] 를 물린다 — 로그아웃/세션 교체로 무효화된 액세스 토큰은
 * 서명이 멀쩡해도 이 빈을 통해서만 걸러진다.
 *
 * 리프레시 토큰도 서명·만료는 액세스 토큰과 같은 키로 유효하게 검증된다 — `typ` 클레임만
 * 다르다. 이 디코더는 리소스 서버(Authorization 헤더) 전용이므로 `typ` 이 `"access"` 인
 * 토큰만 통과시켜야 한다. 그렇지 않으면 리프레시 토큰을 그대로 Bearer 로 흘려보내
 * 블랙리스트/단일 기기 정책(둘 다 액세스 토큰 jti 기준)을 우회할 수 있다.
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
        // 여기에 typ 검증기와 블랙리스트 검증기를 더해 표준 파이프라인 하나로 합친다.
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefault(),
                JwtClaimValidator<String>(CLAIM_TYPE) { it == TYPE_ACCESS },
                JwtBlacklistValidator(sessions),
            ),
        )

        return decoder
    }
}
