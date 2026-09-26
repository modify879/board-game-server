package com.jsm.boardgame.user.application.command.usecase

import java.time.Instant

/**
 * 로그인·갱신 유스케이스가 돌려주는 토큰. 출력 포트의 IssuedTokens 와 달리 accessTokenId(jti) 가 없다 —
 * jti 는 세션 무효화를 위한 서버 내부 식별자라 application 경계 밖으로 나가면 안 된다.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant,
)
