package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.user.infrastructure.security.config.JwtProperties
import com.jsm.boardgame.user.domain.model.UserRole
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

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

        val tokens = issuer.issue(userId = 42L, role = UserRole.USER)

        val claims = SignedJWT.parse(tokens.accessToken).jwtClaimsSet
        assertEquals("42", claims.subject)
    }

    @Test
    fun `발급한 액세스 토큰에 USER role 클레임이 실린다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val tokens = issuer.issue(userId = 1L, role = UserRole.USER)

        val claims = SignedJWT.parse(tokens.accessToken).jwtClaimsSet
        assertEquals("USER", claims.getStringClaim("role"))
    }

    @Test
    fun `발급한 액세스 토큰에 ADMIN role 클레임이 실린다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val tokens = issuer.issue(userId = 1L, role = UserRole.ADMIN)

        val claims = SignedJWT.parse(tokens.accessToken).jwtClaimsSet
        assertEquals("ADMIN", claims.getStringClaim("role"))
    }

    @Test
    fun `두 번 발급하면 accessTokenId 가 서로 다르다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val first = issuer.issue(userId = 1L, role = UserRole.USER)
        val second = issuer.issue(userId = 1L, role = UserRole.USER)

        assertNotEquals(first.accessTokenId, second.accessTokenId)
    }

    @Test
    fun `두 번 발급하면 리프레시 토큰이 서로 다르다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val first = issuer.issue(userId = 1L, role = UserRole.USER)
        val second = issuer.issue(userId = 1L, role = UserRole.USER)

        assertNotEquals(first.refreshToken, second.refreshToken)
    }

    @Test
    fun `발급한 리프레시 토큰은 JWT 가 아니다`() {
        val issuer = JwtTokenIssuer(properties(), Clock.systemUTC())

        val tokens = issuer.issue(userId = 1L, role = UserRole.USER)

        assertFalse(tokens.refreshToken.contains("."))
        assertEquals(43, tokens.refreshToken.length)
    }
}
