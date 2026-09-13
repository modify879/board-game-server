package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.domain.exception.InvalidRefreshTokenException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * 발급한 모든 리프레시 토큰을 userId 로 영구히 기억한다 — 회전 이후에도 "옛 토큰"이라는
 * 사실만 잊지 않는다(실제 JWT 서명은 회전 후에도 유효하다). 세션 스토어의 "이게 최신인가"
 * 판단과는 별개다. 이래야 재사용 탐지 테스트가 의미를 갖는다.
 *
 * 파일 최상위 클래스는 private 이어도 JVM 클래스명이 그대로 노출되므로,
 * 같은 패키지의 다른 테스트 파일과 이름이 겹치면 실제로 충돌한다(Redeclaration).
 * 그래서 Refresh 접두어로 파일 간 이름 충돌을 피한다.
 */
private class RefreshFakeAuthTokenIssuer : AuthTokenIssuer {
    private var counter = 0
    private val issuedRefreshTokens = mutableMapOf<String, Long>()

    override fun issue(userId: Long): IssuedTokens {
        counter += 1
        val refreshToken = "rt-$counter"
        val accessToken = "at-$counter"
        issuedRefreshTokens[refreshToken] = userId
        return IssuedTokens(
            accessToken = accessToken,
            accessTokenId = "jti-$counter",
            accessTokenExpiresAt = Instant.now().plusSeconds(1_800),
            refreshToken = refreshToken,
            refreshTokenExpiresAt = Instant.now().plusSeconds(1_209_600),
        )
    }

    override fun userIdFromRefreshToken(refreshToken: String): Long? = issuedRefreshTokens[refreshToken]
}

private class RefreshInMemoryAuthSessionStore : AuthSessionStore {
    private val sessions = mutableMapOf<Long, AuthSession>()
    private val blacklist = mutableMapOf<String, Instant>()

    override fun start(userId: Long, session: AuthSession) {
        sessions[userId] = session
    }

    override fun matchesRefreshToken(userId: Long, refreshToken: String): Boolean =
        sessions[userId]?.refreshToken == refreshToken

    override fun currentAccessTokenId(userId: Long): String? = sessions[userId]?.accessTokenId

    override fun clear(userId: Long) {
        sessions.remove(userId)
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        blacklist[accessTokenId] = expiresAt
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = blacklist.containsKey(accessTokenId)
}

class RefreshTokenServiceTest {

    private val tokenIssuer = RefreshFakeAuthTokenIssuer()
    private val sessions = RefreshInMemoryAuthSessionStore()
    private val service = RefreshTokenService(tokenIssuer, sessions, Duration.ofMinutes(30))

    private fun loginSession(userId: Long): IssuedTokens {
        val tokens = tokenIssuer.issue(userId)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))
        return tokens
    }

    @Test
    fun `유효한 리프레시 토큰으로 갱신하면 새 토큰이 발급되고 옛 리프레시 토큰은 더 이상 통하지 않는다`() {
        val userId = 1L
        val first = loginSession(userId)

        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))

        assertNotEquals(first.refreshToken, rotated.refreshToken)
        assertNotEquals(first.accessToken, rotated.accessToken)
        assertFalse(sessions.matchesRefreshToken(userId, first.refreshToken))
    }

    @Test
    fun `회전된 옛 리프레시 토큰을 재사용하면 InvalidRefreshTokenException 이 발생하고 세션 전체가 폐기된다`() {
        val userId = 2L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))

        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(first.refreshToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)

        // 재사용 탐지로 세션 전체가 폐기됐으므로, 아직 회수하지 않은 최신 토큰마저 통하지 않는다.
        assertFalse(sessions.matchesRefreshToken(userId, rotated.refreshToken))
        assertNull(sessions.currentAccessTokenId(userId))
    }

    @Test
    fun `서명 검증에 실패하는 리프레시 토큰은 거부된다`() {
        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand("forged-or-unknown-refresh-token"))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
    }

    @Test
    fun `액세스 토큰을 리프레시 자리에 넣으면 거부된다`() {
        val userId = 3L
        val issued = loginSession(userId)

        // 페이크 발급기는 발급한 refreshToken 문자열만 등록하므로, accessToken 문자열을
        // userIdFromRefreshToken 에 넣으면 등록되지 않은 값이라 null 이 나온다 — 타입 혼동 거부를 재현한다.
        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(issued.accessToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
    }
}
