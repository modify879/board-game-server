package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.user.infrastructure.security.config.JwtProperties
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.domain.model.UserRole
import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * HS256 은 대칭키의 강도가 곧 서명 안전성이므로 최소 256비트(32바이트)를 강제한다.
 * RFC 7518 §3.2 요구사항이며, 짧은 키를 그냥 통과시키면 운영에서 서명이 쉽게 위조된다.
 */
private const val MIN_SECRET_BYTES = 32

private const val REFRESH_TOKEN_BYTES = 32

/**
 * 액세스 토큰을 스프링 시큐리티의 Nimbus 기반 [JwtEncoder] 로 HS256 JWT 로 발급한다.
 * 발급자와 검증자가 같은 서버이므로 대칭키로 충분하다 — 비대칭키가 주는 이점(검증자에게 공개키만 배포)이 없다.
 */
@Component
class JwtTokenIssuer(
    properties: JwtProperties,
    private val clock: Clock,
) : AuthTokenIssuer {

    private val secretKey = run {
        val bytes = properties.secret.toByteArray(Charsets.UTF_8)
        check(bytes.size >= MIN_SECRET_BYTES) {
            "app.jwt.secret 은 HS256 서명에 최소 ${MIN_SECRET_BYTES}바이트가 필요합니다 " +
                "(현재 ${bytes.size}바이트). APP_JWT_SECRET 환경변수나 application.yaml 의 app.jwt.secret 을 더 긴 값으로 설정하세요."
        }
        SecretKeySpec(bytes, "HmacSHA256")
    }

    private val accessTokenTtl = properties.accessTokenTtl
    private val refreshTokenTtl = properties.refreshTokenTtl

    private val encoder: JwtEncoder = NimbusJwtEncoder(ImmutableSecret(secretKey))

    private val random = SecureRandom()

    override fun issue(userId: Long, role: UserRole): IssuedTokens {
        val now = Instant.now(clock)
        val subject = userId.toString()

        val accessTokenId = UUID.randomUUID().toString()
        val accessTokenExpiresAt = now.plus(accessTokenTtl)
        val accessToken = encode(
            subject = subject,
            jti = accessTokenId,
            role = role,
            issuedAt = now,
            expiresAt = accessTokenExpiresAt,
        )

        val refreshTokenExpiresAt = now.plus(refreshTokenTtl)
        val refreshToken = randomRefreshToken()

        return IssuedTokens(
            accessToken = accessToken,
            accessTokenId = accessTokenId,
            accessTokenExpiresAt = accessTokenExpiresAt,
            refreshToken = refreshToken,
            refreshTokenExpiresAt = refreshTokenExpiresAt,
        )
    }

    private fun randomRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun encode(subject: String, jti: String, role: UserRole, issuedAt: Instant, expiresAt: Instant): String {
        val claims = JwtClaimsSet.builder()
            .subject(subject)
            .id(jti)
            .claim("role", role.name)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build()

        // Specify HS256 in the header to prevent "Failed to select a JWK signing key" error with ImmutableSecret.
        return encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims),
        ).tokenValue
    }
}
