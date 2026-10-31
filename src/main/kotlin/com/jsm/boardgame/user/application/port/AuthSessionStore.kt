package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import java.time.Instant

/**
 * 단일 기기 정책이므로 사용자당 세션은 하나다. [start] 는 기존 세션을 덮어쓴다.
 *
 * 리프레시 토큰을 어떻게 보관할지(원문 vs 해시)는 구현이 정한다.
 * 그래서 이 포트는 리프레시 토큰 원문을 돌려주지 않는다 — 대조는 [rotate] 안에서만 이루어지고,
 * 호출자는 그 결과([RotationResult])로만 통과 여부를 알 수 있다.
 */
interface AuthSessionStore {
    /** 새 세션을 연다. 같은 사용자의 기존 세션은 덮어써진다. 직전 토큰 유예 칸은 비어서 시작한다. */
    fun start(userId: UserId, session: AuthSession)

    /** 현재 세션의 액세스 토큰 jti. 세션이 없으면 null. */
    fun currentAccessTokenId(userId: UserId): String?

    /** 세션을 폐기한다. 리프레시 토큰이 더 이상 통하지 않는다. */
    fun clear(userId: UserId)

    /** 액세스 토큰을 만료 시각까지 무효화한다. */
    fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant)

    fun isAccessTokenBlacklisted(accessTokenId: String): Boolean

    /**
     * 제시된 리프레시 토큰이 현재 토큰이면 세션을 [next] 로 원자적으로 교체한다([RotationResult.Rotated]).
     * [nextAccessToken]·[nextAccessTokenExpiresAt]·[nextRole] 은 이 회전의 후속 액세스 토큰과 박힌 역할이다 — 구현이 재생용으로 보관한다.
     *
     * 직전(밀려난) 토큰이 유예 안에 다시 제시되면 **다시 회전하지 않고** 처음 회전이 발급한 후속 토큰을
     * 그대로 돌려준다([RotationResult.Replayed], 멱등 재생). 같은 쿠키로 동시에 온 갱신 요청이 서로를
     * 로그아웃시키지 않게 하려는 것이다. 유예가 지났거나 더 오래된 토큰, 세션이 없으면 [RotationResult.Mismatch].
     *
     * 검사와 교체를 한 연산으로 처리해 동시 갱신 경합을 막는다. 회전 후의 직전 칸은 **호출자가 정하지 않는다** —
     * 구현이 이번에 밀려난 현재 토큰을 채운다.
     */
    fun rotate(
        userId: UserId,
        presentedRefreshToken: String,
        next: AuthSession,
        nextAccessToken: String,
        nextAccessTokenExpiresAt: Instant,
        nextRole: UserRole,
    ): RotationResult

    /** 적중해도 유효하다는 뜻이 아니다 — 재사용 판정은 [rotate] 가 한다. */
    fun userIdForRefreshToken(refreshToken: String): UserId?
}

data class AuthSession(
    val accessTokenId: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

sealed interface RotationResult {
    /** 교체 성공. [previousAccessTokenId] 는 교체 전 세션의 jti. */
    data class Rotated(val previousAccessTokenId: String?) : RotationResult

    /** 유예 안의 직전 토큰 재제시. 처음 회전이 발급한 후속 토큰을 그대로 돌려준다 — 새로 발급·폐기된 것은 없다. */
    data class Replayed(
        val accessTokenId: String,
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAt: Instant,
        val refreshTokenExpiresAt: Instant,
        /** 후속 액세스 토큰에 박힌 역할 — 재생 시 역할 변경 재확인에 쓴다. */
        val role: UserRole,
    ) : RotationResult

    /** 현재·직전(유예 안) 어디와도 일치하지 않는다. 재사용 또는 탈취. */
    data object Mismatch : RotationResult
}
