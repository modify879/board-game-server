package com.jsm.boardgame.user.presentation.rest.response

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import java.time.Instant

data class TokenResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
) {
    companion object {
        fun from(tokens: AuthTokens): TokenResponse =
            TokenResponse(
                accessToken = tokens.accessToken,
                accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            )
    }
}
