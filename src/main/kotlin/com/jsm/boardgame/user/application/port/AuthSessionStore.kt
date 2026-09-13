package com.jsm.boardgame.user.application.port

import java.time.Instant

/**
 * 단일 기기 정책이므로 사용자당 세션은 하나다. [start] 는 기존 세션을 덮어쓴다.
 *
 * 리프레시 토큰을 어떻게 보관할지(원문 vs 해시)는 구현이 정한다.
 * 그래서 이 포트는 값을 돌려주지 않고 [matchesRefreshToken] 으로 대조만 한다.
 */
interface AuthSessionStore {
    /** 새 세션을 연다. 같은 사용자의 기존 세션은 덮어써진다. */
    fun start(userId: Long, session: AuthSession)

    /** 저장된 리프레시 토큰과 일치하는지. 세션이 없으면 false. */
    fun matchesRefreshToken(userId: Long, refreshToken: String): Boolean

    /** 현재 세션의 액세스 토큰 jti. 세션이 없으면 null. */
    fun currentAccessTokenId(userId: Long): String?

    /** 세션을 폐기한다. 리프레시 토큰이 더 이상 통하지 않는다. */
    fun clear(userId: Long)

    /** 액세스 토큰을 만료 시각까지 무효화한다. */
    fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant)

    fun isAccessTokenBlacklisted(accessTokenId: String): Boolean
}

data class AuthSession(
    val accessTokenId: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    /** 이번 회전에서 소모된 직전 토큰. 짧은 유예 동안만 재사용을 허용한다. 신규 로그인은 null. */
    val previousRefreshToken: String? = null,
)
