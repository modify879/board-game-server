package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.port.IssuedTokens
import java.time.Instant

/**
 * accessTokenId(jti) 는 세션 무효화를 위한 서버 내부 식별자다. 클라이언트에 노출하지 않는다.
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Instant,
) {
    companion object {
        fun from(tokens: IssuedTokens): TokenResponse =
            TokenResponse(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            )
    }
}
