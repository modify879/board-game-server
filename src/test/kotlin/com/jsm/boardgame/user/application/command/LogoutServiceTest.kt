package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.domain.exception.InvalidRefreshTokenException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class LogoutFakeAuthTokenIssuer : AuthTokenIssuer {
    private var sequence = 0

    override fun issue(userId: Long): IssuedTokens {
        sequence += 1
        return IssuedTokens(
            accessToken = "access:$userId:$sequence",
            accessTokenId = "jti-$sequence",
            accessTokenExpiresAt = Instant.now().plusSeconds(900),
            refreshToken = "refresh:$userId:$sequence",
            refreshTokenExpiresAt = Instant.now().plusSeconds(2_592_000),
        )
    }

    override fun userIdFromRefreshToken(refreshToken: String): Long? {
        if (!refreshToken.startsWith("refresh:")) return null
        return refreshToken.split(":").getOrNull(1)?.toLongOrNull()
    }
}

private class LogoutFakeAuthSessionStore : AuthSessionStore {
    private val sessions = mutableMapOf<Long, AuthSession>()
    private val blacklisted = mutableSetOf<String>()

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
        blacklisted += accessTokenId
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = accessTokenId in blacklisted
}

class LogoutServiceTest {

    private val sessions = LogoutFakeAuthSessionStore()
    private val tokenIssuer = LogoutFakeAuthTokenIssuer()
    private val service = LogoutService(sessions, Duration.ofMinutes(30))

    private fun loggedIn(userId: Long): IssuedTokens {
        val issued = tokenIssuer.issue(userId)
        sessions.start(
            userId,
            AuthSession(
                accessTokenId = issued.accessTokenId,
                refreshToken = issued.refreshToken,
                refreshTokenExpiresAt = issued.refreshTokenExpiresAt,
            ),
        )
        return issued
    }

    @Test
    fun `로그아웃 후 리프레시가 안 통한다`() {
        val issued = loggedIn(1L)

        service.logout(1L)

        val refreshService = RefreshTokenService(tokenIssuer, sessions, Duration.ofMinutes(30))
        assertFailsWith<InvalidRefreshTokenException> {
            refreshService.refresh(RefreshTokenCommand(issued.refreshToken))
        }
    }

    @Test
    fun `로그아웃하면 현재 액세스 토큰이 블랙리스트에 들어간다`() {
        val issued = loggedIn(1L)

        service.logout(1L)

        assertTrue(sessions.isAccessTokenBlacklisted(issued.accessTokenId))
    }

    @Test
    fun `세션 없는 로그아웃이 예외 없이 끝난다`() {
        service.logout(999L)
    }
}
