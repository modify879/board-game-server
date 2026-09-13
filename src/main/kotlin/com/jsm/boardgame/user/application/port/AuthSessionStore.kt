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

    /**
     * 제시된 리프레시 토큰이 현재 토큰(또는 유예 중인 직전 토큰)과 일치하면
     * 세션을 [next] 로 원자적으로 교체한다.
     *
     * 검사와 교체를 한 연산으로 처리해 동시 갱신 경합을 막는다. 나누면 두 요청이 모두
     * 검사를 통과해 각자 토큰을 발급하고, 나중 쓰기가 이겨 진 쪽 클라이언트는
     * 저장되지 않은 토큰을 받는다.
     */
    fun rotate(userId: Long, presentedRefreshToken: String, next: AuthSession): RotationResult
}

data class AuthSession(
    val accessTokenId: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    /** 이번 회전에서 소모된 직전 토큰. 짧은 유예 동안만 재사용을 허용한다. 신규 로그인은 null. */
    val previousRefreshToken: String? = null,
)

sealed interface RotationResult {
    /** 교체 성공. [previousAccessTokenId] 는 교체 전 세션의 jti. */
    data class Rotated(val previousAccessTokenId: String?) : RotationResult

    /** 현재·직전 어디와도 일치하지 않는다. 재사용 또는 탈취. */
    data object Mismatch : RotationResult
}
