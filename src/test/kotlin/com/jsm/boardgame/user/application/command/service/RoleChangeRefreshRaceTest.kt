package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleCommand
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenCommand
import com.jsm.boardgame.user.application.exception.InvalidRefreshTokenException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RaceUsers : UserRepository {
    private val stored = mutableMapOf<Long, Pair<UserRole, String>>()
    var beforeSave: (() -> Unit)? = null

    fun put(id: Long, role: UserRole) { stored[id] = role to "n$id" }
    fun roleOf(id: Long): UserRole = stored.getValue(id).first

    override fun findByUsername(username: Username): User? = null
    override fun findById(id: UserId): User? = stored[id.value]?.let { (role, _) ->
        User.reconstitute(id, Username.reconstitute("user_${id.value}"), PasswordHash("h"), Nickname.reconstitute("닉네임${id.value}"), null, role)
    }
    override fun existsByUsername(username: Username): Boolean = false
    override fun existsByNickname(nickname: Nickname): Boolean = false
    override fun save(user: User): User {
        beforeSave?.let { hook -> beforeSave = null; hook() }
        stored[user.id!!.value] = user.role to "n"
        return user
    }
}

private class RaceSessions : AuthSessionStore {
    private val sessions = mutableMapOf<UserId, AuthSession>()
    private val index = mutableMapOf<String, UserId>()
    private val blacklist = mutableSetOf<String>()
    var beforeRotate: (() -> Unit)? = null

    override fun start(userId: UserId, session: AuthSession) { sessions[userId] = session; index[session.refreshToken] = userId }
    override fun currentAccessTokenId(userId: UserId): String? = sessions[userId]?.accessTokenId
    override fun clear(userId: UserId) { sessions.remove(userId) }
    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) { blacklist += accessTokenId }
    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = accessTokenId in blacklist
    override fun userIdForRefreshToken(refreshToken: String): UserId? = index[refreshToken]
    override fun rotate(userId: UserId, presentedRefreshToken: String, next: AuthSession): RotationResult {
        beforeRotate?.let { hook -> beforeRotate = null; hook() }
        val current = sessions[userId] ?: return RotationResult.Mismatch
        if (current.refreshToken != presentedRefreshToken) return RotationResult.Mismatch
        start(userId, next)
        return RotationResult.Rotated(current.accessTokenId)
    }
}

private class RaceIssuer : AuthTokenIssuer {
    private var n = 0
    override fun issue(userId: UserId, role: UserRole): IssuedTokens {
        n += 1
        return IssuedTokens("at-$n", "jti-$n", Instant.now().plusSeconds(1_800), "rt-$n", Instant.now().plusSeconds(1_000_000))
    }
}

private class RaceRealtime : RealtimeConnections {
    override fun closeAllOf(userId: UserId) {}
    override fun closeAfterGrace(accessTokenId: String) {}
}

class RoleChangeRefreshRaceTest {

    private val users = RaceUsers()
    private val sessions = RaceSessions()
    private val issuer = RaceIssuer()
    private val ttl = Duration.ofMinutes(30)
    private val refresh = RefreshTokenService(issuer, sessions, RaceRealtime(), users, ttl, Clock.systemUTC())
    private val changeRole = ChangeUserRoleService(users, sessions, RaceRealtime(), ttl, Clock.systemUTC())

    private fun login(role: UserRole): IssuedTokens {
        users.put(1L, role)
        val t = issuer.issue(UserId(1L), role)
        sessions.start(UserId(1L), AuthSession(t.accessTokenId, t.refreshToken, t.refreshTokenExpiresAt))
        return t
    }

    private fun roleChange(to: UserRole) = ChangeUserRoleCommand(1L, to.name)

    private fun `역할 변경이 리프레시의 발급과 rotate 사이에 끝나면 리프레시는 거부되고 새 토큰이 폐기된다`(from: UserRole, to: UserRole) {
        val first = login(from)
        // 리프레시가 옛 역할을 읽고 발급한 뒤, rotate 직전에 역할 변경이 통째로 끝난다. 재확인은 save 이후다.
        sessions.beforeRotate = { changeRole.changeRole(roleChange(to)) }

        val e = assertFailsWith<InvalidRefreshTokenException> { refresh.refresh(RefreshTokenCommand(first.refreshToken)) }

        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
        assertEquals(to, users.roleOf(1L))
        assertTrue(sessions.isAccessTokenBlacklisted("jti-2")) // 옛 역할로 발급된 새 토큰
        assertNull(sessions.currentAccessTokenId(UserId(1L)))
    }

    private fun `리프레시가 save 이전에 끝나면 역할 변경의 두 번째 폐기가 그 토큰을 죽인다`(from: UserRole, to: UserRole) {
        val first = login(from)
        var refreshed: com.jsm.boardgame.user.application.command.usecase.AuthTokens? = null
        // 역할 변경의 첫 폐기 뒤, save 직전에 리프레시가 재확인까지 전부 끝낸다(역할은 아직 옛 값이라 성공).
        users.beforeSave = { refreshed = refresh.refresh(RefreshTokenCommand(first.refreshToken)) }

        changeRole.changeRole(roleChange(to))

        assertTrue(refreshed != null)
        assertEquals(to, users.roleOf(1L))
        assertTrue(sessions.isAccessTokenBlacklisted("jti-2"))
    }

    @Test
    fun `강등 - 발급과 rotate 사이에 역할이 바뀌면 리프레시 거부`() =
        `역할 변경이 리프레시의 발급과 rotate 사이에 끝나면 리프레시는 거부되고 새 토큰이 폐기된다`(UserRole.ADMIN, UserRole.USER)

    @Test
    fun `승격 - 발급과 rotate 사이에 역할이 바뀌면 리프레시 거부`() =
        `역할 변경이 리프레시의 발급과 rotate 사이에 끝나면 리프레시는 거부되고 새 토큰이 폐기된다`(UserRole.USER, UserRole.ADMIN)

    @Test
    fun `강등 - save 이전에 끝난 리프레시 토큰은 두 번째 폐기로 죽는다`() =
        `리프레시가 save 이전에 끝나면 역할 변경의 두 번째 폐기가 그 토큰을 죽인다`(UserRole.ADMIN, UserRole.USER)

    @Test
    fun `승격 - save 이전에 끝난 리프레시 토큰은 두 번째 폐기로 죽는다`() =
        `리프레시가 save 이전에 끝나면 역할 변경의 두 번째 폐기가 그 토큰을 죽인다`(UserRole.USER, UserRole.ADMIN)

    @Test
    fun `경합이 없으면 리프레시는 성공하고 세션이 유지된다`() {
        val first = login(UserRole.ADMIN)

        val tokens = refresh.refresh(RefreshTokenCommand(first.refreshToken))

        assertEquals("at-2", tokens.accessToken)
        assertEquals("jti-2", sessions.currentAccessTokenId(UserId(1L)))
        assertFalse(sessions.isAccessTokenBlacklisted("jti-2"))
    }

    @Test
    fun `역할 변경 뒤 리프레시하면 새 역할로 성공한다`() {
        val first = login(UserRole.ADMIN)
        changeRole.changeRole(roleChange(UserRole.USER))

        val tokens = refresh.refresh(RefreshTokenCommand(first.refreshToken))

        assertEquals("jti-2", sessions.currentAccessTokenId(UserId(1L)))
        assertFalse(sessions.isAccessTokenBlacklisted("jti-2"))
        assertTrue(tokens.accessToken.isNotBlank())
    }

    @Test
    fun `두 번째 폐기가 실패하면 예외이고 역할은 저장돼 있으며 재시도로 수렴한다`() {
        login(UserRole.ADMIN)
        var failNext = true
        val flaky = object : AuthSessionStore by sessions {
            var calls = 0
            override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
                calls += 1
                if (calls == 2 && failNext) { failNext = false; throw IllegalStateException("redis down") }
                sessions.blacklistAccessToken(accessTokenId, expiresAt)
            }
        }
        val svc = ChangeUserRoleService(users, flaky, RaceRealtime(), ttl, Clock.systemUTC())

        assertFailsWith<IllegalStateException> { svc.changeRole(roleChange(UserRole.USER)) }
        assertEquals(UserRole.USER, users.roleOf(1L))

        svc.changeRole(roleChange(UserRole.USER))
        assertTrue(sessions.isAccessTokenBlacklisted("jti-1"))
    }
}
