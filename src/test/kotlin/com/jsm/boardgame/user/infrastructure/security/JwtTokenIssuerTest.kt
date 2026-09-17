package com.jsm.boardgame.user.infrastructure.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class JwtTokenIssuerTest {

    private fun properties(
        secret: String = "test-secret-key-at-least-32-bytes-long!!",
        accessTokenTtl: Duration = Duration.ofMinutes(30),
        refreshTokenTtl: Duration = Duration.ofDays(14),
        refreshReuseGrace: Duration = Duration.ofSeconds(5),
    ) = JwtProperties(secret, accessTokenTtl, refreshTokenTtl, refreshReuseGrace)

    @Test
    fun `발급한 액세스 토큰의 sub 가 요청한 userId 다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val tokens = issuer.issue(userId = 42L)

        val claims = SignedJWT.parse(tokens.accessToken).jwtClaimsSet
        assertEquals("42", claims.subject)
    }

    @Test
    fun `발급한 리프레시 토큰을 userIdFromRefreshToken 에 넣으면 같은 userId 가 나온다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val tokens = issuer.issue(userId = 7L)

        assertEquals(7L, issuer.userIdFromRefreshToken(tokens.refreshToken))
    }

    @Test
    fun `액세스 토큰을 userIdFromRefreshToken 에 넣으면 null 이다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val tokens = issuer.issue(userId = 1L)

        assertNull(issuer.userIdFromRefreshToken(tokens.accessToken))
    }

    @Test
    fun `서명이 다른 키로 만든 토큰은 null 이다`() {
        val issuerA = JwtTokenIssuer(properties(secret = "issuer-a-secret-key-at-least-32-bytes!!"), Clock.systemUTC())
        val issuerB = JwtTokenIssuer(properties(secret = "issuer-b-secret-key-at-least-32-bytes!!"), Clock.systemUTC())

        val tokens = issuerA.issue(userId = 99L)

        assertNull(issuerB.userIdFromRefreshToken(tokens.refreshToken))
    }

    @Test
    fun `만료된 리프레시 토큰은 null 이다`() {
        // Jwt(스프링 시큐리티) 생성자는 expiresAt > issuedAt 을 강제한다. TTL 을 음수로 주면
        // "이미 만료된 토큰"을 issue() 자체가 발급하지 못해 이 케이스를 재현할 수 없다.
        // 그래서 발급기를 거치지 않고 Nimbus 로 이미 만료된 토큰을 직접 서명해 만들고,
        // userIdFromRefreshToken 의 검증 경로만 확인한다 — 발급기 내부 구현에 덜 의존한다.
        val props = properties()
        val issuer = JwtTokenIssuer(props, Clock.systemUTC())

        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject("5")
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now.minusSeconds(120)))
            .expirationTime(Date.from(now.minusSeconds(60)))
            .claim("typ", "refresh")
            .build()
        val expiredRefreshToken = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
            .apply { sign(MACSigner(props.secret.toByteArray(Charsets.UTF_8))) }
            .serialize()

        assertNull(issuer.userIdFromRefreshToken(expiredRefreshToken))
    }

    @Test
    fun `쓰레기 문자열은 예외 없이 null 이다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        assertNull(issuer.userIdFromRefreshToken("이건-JWT-가-아니다"))
    }

    @Test
    fun `두 번 발급하면 accessTokenId 가 서로 다르다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val first = issuer.issue(userId = 1L)
        val second = issuer.issue(userId = 1L)

        assertNotEquals(first.accessTokenId, second.accessTokenId)
    }

    @Test
    fun `32바이트 미만 키로 생성하면 명확한 예외가 난다`() {
        val exception = assertFailsWith<IllegalStateException> {
            JwtTokenIssuer(properties(secret = "too-short"), Clock.systemUTC())
        }

        assertEquals(
            true,
            exception.message?.contains("32바이트") == true,
            "예외 메시지가 최소 키 길이를 명시해야 한다: ${exception.message}",
        )
    }
}
