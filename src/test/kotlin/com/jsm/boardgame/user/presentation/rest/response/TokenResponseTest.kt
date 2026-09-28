package com.jsm.boardgame.user.presentation.rest.response

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenResponseTest {

    @Test
    fun `accessTokenExpiresInMs 는 accessTokenExpiresAt 까지 남은 ms 다`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val tokens = AuthTokens(
            accessToken = "access",
            refreshToken = "refresh",
            accessTokenExpiresAt = now.plus(Duration.ofMinutes(10)),
            refreshTokenExpiresAt = now.plus(Duration.ofDays(7)),
        )

        val response = TokenResponse.from(tokens, clock)

        assertEquals(Duration.ofMinutes(10).toMillis(), response.accessTokenExpiresInMs)
    }

    @Test
    fun `이미 만료된 토큰이어도 accessTokenExpiresInMs 는 0 미만으로 내려가지 않는다`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val tokens = AuthTokens(
            accessToken = "access",
            refreshToken = "refresh",
            accessTokenExpiresAt = now.minus(Duration.ofMinutes(1)),
            refreshTokenExpiresAt = now.plus(Duration.ofDays(7)),
        )

        val response = TokenResponse.from(tokens, clock)

        assertEquals(0L, response.accessTokenExpiresInMs)
    }
}
