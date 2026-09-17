package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * HS256 은 대칭키의 강도가 곧 서명 안전성이므로 최소 256비트(32바이트)를 강제한다.
 * RFC 7518 §3.2 요구사항이며, 짧은 키를 그냥 통과시키면 운영에서 서명이 쉽게 위조된다.
 */
private const val MIN_SECRET_BYTES = 32

// 리소스 서버 디코더(`JwtDecoderConfig`)가 액세스 토큰인지 검증할 때도 같은 값을 써야 하므로
// internal 로 열어 공유한다. 문자열을 양쪽에 중복해서 박지 않는다.
internal const val CLAIM_TYPE = "typ"
internal const val TYPE_ACCESS = "access"
private const val TYPE_REFRESH = "refresh"

/**
 * 스프링 시큐리티의 Nimbus 기반 [JwtEncoder]/[JwtDecoder] 로 HS256 JWT 를 발급·검증한다.
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

    private val decoder: JwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
        .macAlgorithm(MacAlgorithm.HS256)
        .build()

    override fun issue(userId: Long): IssuedTokens {
        val now = Instant.now(clock)
        val subject = userId.toString()

        val accessTokenId = UUID.randomUUID().toString()
        val accessTokenExpiresAt = now.plus(accessTokenTtl)
        val accessToken = encode(
            subject = subject,
            jti = accessTokenId,
            type = TYPE_ACCESS,
            issuedAt = now,
            expiresAt = accessTokenExpiresAt,
        )

        val refreshTokenExpiresAt = now.plus(refreshTokenTtl)
        val refreshToken = encode(
            subject = subject,
            jti = UUID.randomUUID().toString(),
            type = TYPE_REFRESH,
            issuedAt = now,
            expiresAt = refreshTokenExpiresAt,
        )

        return IssuedTokens(
            accessToken = accessToken,
            accessTokenId = accessTokenId,
            accessTokenExpiresAt = accessTokenExpiresAt,
            refreshToken = refreshToken,
            refreshTokenExpiresAt = refreshTokenExpiresAt,
        )
    }

    override fun userIdFromRefreshToken(refreshToken: String): Long? {
        val jwt = try {
            decoder.decode(refreshToken)
        } catch (ex: JwtException) {
            return null
        }

        if (jwt.getClaimAsString(CLAIM_TYPE) != TYPE_REFRESH) {
            return null
        }

        return jwt.subject?.toLongOrNull()
    }

    private fun encode(subject: String, jti: String, type: String, issuedAt: Instant, expiresAt: Instant): String {
        val claims = JwtClaimsSet.builder()
            .subject(subject)
            .id(jti)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(CLAIM_TYPE, type)
            .build()

        // Decoder is already configured with HS256, but encoder defaults to RS256 without an explicit header.
        // Specify HS256 in the header to prevent "Failed to select a JWK signing key" error with ImmutableSecret.
        return encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims),
        ).tokenValue
    }
}
