package com.jsm.boardgame.user.presentation.rest.response

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class TokenResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    /** [accessTokenExpiresAt] 까지 남은 ms. 클라이언트는 받은 순간부터 자기 단조 시계로 센다. */
    val accessTokenExpiresInMs: Long,
) {
    companion object {
        fun from(tokens: AuthTokens, clock: Clock): TokenResponse =
            TokenResponse(
                accessToken = tokens.accessToken,
                accessTokenExpiresAt = tokens.accessTokenExpiresAt,
                accessTokenExpiresInMs = Duration.between(Instant.now(clock), tokens.accessTokenExpiresAt).toMillis().coerceAtLeast(0),
            )
    }
}
