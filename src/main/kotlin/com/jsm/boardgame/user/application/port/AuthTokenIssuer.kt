package com.jsm.boardgame.user.application.port

import java.time.Instant

interface AuthTokenIssuer {
    fun issue(userId: Long): IssuedTokens

    /**
     * 리프레시 토큰에서 사용자 식별자를 꺼낸다. 서명이 틀리거나 만료됐으면 null.
     * 이 단계를 통과해도 저장소와 대조해야 한다 — 회전된 옛 토큰도 서명은 유효하기 때문이다.
     */
    fun userIdFromRefreshToken(refreshToken: String): Long?
}

data class IssuedTokens(
    val accessToken: String,
    /** 액세스 토큰의 jti. 로그아웃·세션 교체 때 블랙리스트 키가 된다. */
    val accessTokenId: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
