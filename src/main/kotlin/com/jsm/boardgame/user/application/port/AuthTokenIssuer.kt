package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.UserRole
import java.time.Instant

interface AuthTokenIssuer {
    fun issue(userId: Long, role: UserRole): IssuedTokens
}

data class IssuedTokens(
    val accessToken: String,
    /** 액세스 토큰의 jti. 로그아웃·세션 교체 때 블랙리스트 키가 된다. */
    val accessTokenId: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
