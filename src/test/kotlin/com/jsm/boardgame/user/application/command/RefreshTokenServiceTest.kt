package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.exception.InvalidRefreshTokenException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

/**
 * 실제 [com.jsm.boardgame.user.infrastructure.security.RedisAuthSessionStore] 와 같은 유예 규칙을
 * 흉내 낸다 — previousRefreshToken 은 한 세대만 기억하고, 그 세대에 한해서만 [refreshReuseGrace]
 * 동안 재사용을 허용한다. 두 세대 전 토큰은 애초에 기억하지 않으므로 유예와 무관하게 거부된다.
 */
private class RefreshInMemoryAuthSessionStore(
    private val refreshReuseGrace: Duration = Duration.ofSeconds(30),
) : AuthSessionStore {
    private val sessions = mutableMapOf<Long, AuthSession>()
    private val graceExpiresAt = mutableMapOf<Long, Instant>()
    private val blacklist = mutableMapOf<String, Instant>()

    override fun start(userId: Long, session: AuthSession) {
        sessions[userId] = session
        graceExpiresAt[userId] = if (session.previousRefreshToken != null) {
            Instant.now().plus(refreshReuseGrace)
        } else {
            Instant.EPOCH
        }
    }

    override fun matchesRefreshToken(userId: Long, refreshToken: String): Boolean {
        val session = sessions[userId] ?: return false
        if (session.refreshToken == refreshToken) return true

        val previous = session.previousRefreshToken ?: return false
        if (previous != refreshToken) return false

        return Instant.now().isBefore(graceExpiresAt[userId])
    }

    override fun currentAccessTokenId(userId: Long): String? = sessions[userId]?.accessTokenId

    override fun clear(userId: Long) {
        sessions.remove(userId)
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        blacklist[accessTokenId] = expiresAt
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = blacklist.containsKey(accessTokenId)

    /**
     * 실제 [com.jsm.boardgame.user.infrastructure.security.RedisAuthSessionStore.rotate] 와 같은 규칙으로
     * 대조와 교체를 한 번에 수행한다 — 현재 토큰이거나 유예 안의 직전 토큰이면 교체하고, 아니면 세션을
     * 건드리지 않은 채 Mismatch 를 돌려준다.
     */
    override fun rotate(userId: Long, presentedRefreshToken: String, next: AuthSession): RotationResult {
        val session = sessions[userId] ?: return RotationResult.Mismatch

        val matches = if (session.refreshToken == presentedRefreshToken) {
            true
        } else {
            val previous = session.previousRefreshToken
            previous != null && previous == presentedRefreshToken && Instant.now().isBefore(graceExpiresAt[userId])
        }

        if (!matches) return RotationResult.Mismatch

        val previousAccessTokenId = session.accessTokenId
        start(userId, next)
        return RotationResult.Rotated(previousAccessTokenId)
    }
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
    fun `유효한 리프레시 토큰으로 갱신하면 새 토큰이 발급된다`() {
        val userId = 1L
        val first = loginSession(userId)

        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))

        assertNotEquals(first.refreshToken, rotated.refreshToken)
        assertNotEquals(first.accessToken, rotated.accessToken)
        // 직전 한 세대는 응답 유실 재시도를 위한 유예 대상이라 아직 통과한다 —
        // "완전히 무효화됨"은 두 세대 전부터다. 아래 두 세대 전 테스트가 그 경계를 검증한다.
        assertTrue(sessions.matchesRefreshToken(userId, first.refreshToken))
    }

    @Test
    fun `유예 안에서 회전된 옛 리프레시 토큰으로 재시도하면 세션이 폐기되지 않고 계속 갱신할 수 있다`() {
        val userId = 6L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))

        // 회전 응답이 유실돼 클라이언트가 같은(옛) 토큰으로 재시도하는 상황을 재현한다.
        val retried = service.refresh(RefreshTokenCommand(first.refreshToken))

        assertNotEquals(rotated.refreshToken, retried.refreshToken)
        assertNotNull(sessions.currentAccessTokenId(userId))

        // 세션이 폐기되지 않았으므로 최신 토큰으로 계속 갱신할 수 있다.
        val next = service.refresh(RefreshTokenCommand(retried.refreshToken))
        assertNotEquals(retried.refreshToken, next.refreshToken)
    }

    @Test
    fun `두 세대 전 리프레시 토큰을 쓰면 유예와 무관하게 세션이 폐기된다`() {
        val userId = 7L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))
        service.refresh(RefreshTokenCommand(rotated.refreshToken))

        // first 는 이제 두 세대 전 토큰이다 — 유예는 바로 직전 한 세대에만 적용되므로
        // 유예 여부와 무관하게 재사용 탐지가 그대로 동작해야 한다.
        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(first.refreshToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
        assertNull(sessions.currentAccessTokenId(userId))
    }

    @Test
    fun `회전된 옛 리프레시 토큰을 재사용하면 InvalidRefreshTokenException 이 발생하고 세션 전체가 폐기된다`() {
        val userId = 2L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))
        // 유예는 바로 직전 한 세대에만 적용된다. first 가 유예 밖(두 세대 전)이 되도록
        // 한 번 더 회전시켜야, 이 테스트가 순수한 재사용 탐지(유예 대상이 아닌 경우)를 검증한다.
        val rotatedAgain = service.refresh(RefreshTokenCommand(rotated.refreshToken))

        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(first.refreshToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)

        // 재사용 탐지로 세션 전체가 폐기됐으므로, 아직 회수하지 않은 최신 토큰마저 통하지 않는다.
        assertFalse(sessions.matchesRefreshToken(userId, rotatedAgain.refreshToken))
        assertNull(sessions.currentAccessTokenId(userId))
    }

    @Test
    fun `재사용 탐지 시 그 시점까지 살아 있던 액세스 토큰이 clear 이전에 블랙리스트에 등록된다`() {
        val userId = 8L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))
        // 유예는 바로 직전 한 세대에만 적용된다. first 가 두 세대 전이 되도록 한 번 더 회전시켜야
        // 재사용 탐지 경로(clear 이전 블랙리스트 등록)를 탄다.
        val rotatedAgain = service.refresh(RefreshTokenCommand(rotated.refreshToken))

        assertFalse(sessions.isAccessTokenBlacklisted(rotatedAgain.accessTokenId))

        assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(first.refreshToken))
        }

        // clear() 가 세션을 지우기 전에 그 시점의 액세스 토큰을 블랙리스트에 넣어야 한다 —
        // 순서가 뒤바뀌면 currentAccessTokenId 가 이미 null 이라 블랙리스트에 넣을 방법이 없어진다.
        assertTrue(sessions.isAccessTokenBlacklisted(rotatedAgain.accessTokenId))
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
