package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangePasswordCommand
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.CurrentPasswordMismatchException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.exception.InvalidPasswordException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ChangePwFakeUserRepository : UserRepository {
    val stored = mutableMapOf<Long, User>()
    override fun findByUsername(username: Username): User? = stored.values.find { it.username == username }
    override fun findById(id: UserId): User? = stored[id.value]
    override fun existsByUsername(username: Username): Boolean = false
    override fun existsByNickname(nickname: Nickname): Boolean = false
    override fun save(user: User): User { stored[user.id!!.value] = user; return user }
}

private class ChangePwFakeHasher : PasswordHasher {
    override fun hash(raw: RawPassword): PasswordHash = PasswordHash("hashed:${raw.value}")
    override fun matches(raw: RawPassword, hash: PasswordHash): Boolean = hash.value == "hashed:${raw.value}"
}

private class ChangePwFakeLimiter : LoginAttemptLimiter {
    var failures = 0
    var resets = 0
    var thresholdAt = Int.MAX_VALUE
    override fun recordFailure(username: Username, maxFailures: Int, window: Duration): Boolean {
        failures++
        return failures >= thresholdAt
    }
    override fun reset(username: Username) { resets++ }
}

private class ChangePwFakeRealtime : RealtimeConnections {
    val closeAllOfCalls = mutableListOf<UserId>()
    val closeAfterGraceCalls = mutableListOf<String>()
    override fun closeAllOf(userId: UserId) { closeAllOfCalls += userId }
    override fun closeAfterGrace(accessTokenId: String) { closeAfterGraceCalls += accessTokenId }
}

private class ChangePwFakeIssuer : AuthTokenIssuer {
    override fun issue(userId: UserId, role: UserRole) = IssuedTokens(
        accessToken = "new-at",
        accessTokenId = "new-jti",
        accessTokenExpiresAt = Instant.now().plusSeconds(1_800),
        refreshToken = "new-rt",
        refreshTokenExpiresAt = Instant.now().plusSeconds(1_209_600),
    )
}

private class ChangePwFakeSessions : AuthSessionStore {
    val sessions = mutableMapOf<UserId, AuthSession>()
    val blacklist = mutableSetOf<String>()
    override fun start(userId: UserId, session: AuthSession) { sessions[userId] = session }
    override fun currentAccessTokenId(userId: UserId): String? = sessions[userId]?.accessTokenId
    override fun clear(userId: UserId) { sessions.remove(userId) }
    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) { blacklist += accessTokenId }
    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = accessTokenId in blacklist
    override fun rotate(userId: UserId, presentedRefreshToken: String, next: AuthSession, nextAccessToken: String, nextAccessTokenExpiresAt: Instant, nextRole: UserRole): RotationResult =
        throw UnsupportedOperationException()
    override fun userIdForRefreshToken(refreshToken: String): UserId? = null
}

class ChangePasswordServiceTest {

    private val users = ChangePwFakeUserRepository()
    private val limiter = ChangePwFakeLimiter()
    private val realtime = ChangePwFakeRealtime()
    private val sessions = ChangePwFakeSessions()
    private val hasher = ChangePwFakeHasher()
    private val verifier = CurrentPasswordVerifier(
        users, hasher, limiter, LogoutService(sessions, realtime, Duration.ofMinutes(30), Clock.systemUTC()),
        5, Duration.ofMinutes(15), Clock.systemUTC(),
    )
    private val service = ChangePasswordService(
        users, hasher, ChangePwFakeIssuer(), sessions, realtime, limiter, verifier,
        Duration.ofMinutes(30), Clock.systemUTC(),
    )

    private fun seed(locked: Boolean = false) {
        val user = User.reconstitute(
            id = UserId(1),
            username = Username.of("user_01"),
            passwordHash = PasswordHash("hashed:oldpassword1"),
            nickname = Nickname.of("길동이"),
            profileImageKey = null,
            role = UserRole.USER,
        )
        if (locked) user.lock(Instant.now())
        users.stored[1] = user
        sessions.start(UserId(1), AuthSession("old-jti", "old-rt", Instant.now().plusSeconds(1_000)))
    }

    private fun change(current: String = "oldpassword1", new: String = "newpassword1") =
        service.changePassword(ChangePasswordCommand(1L, current, new))

    @Test
    fun `성공하면 비밀번호가 바뀌고 옛 토큰은 블랙리스트에 올라 유예 후 닫히며 새 토큰이 세션이 된다`() {
        seed()

        val tokens = change()

        assertEquals("new-at", tokens.accessToken)
        assertEquals(PasswordHash("hashed:newpassword1"), users.stored[1]!!.passwordHash)
        assertTrue(sessions.isAccessTokenBlacklisted("old-jti"))
        assertEquals(listOf("old-jti"), realtime.closeAfterGraceCalls)
        assertEquals(AuthSession("new-jti", "new-rt", sessions.sessions[UserId(1)]!!.refreshTokenExpiresAt), sessions.sessions[UserId(1)])
        assertEquals(1, limiter.resets)
    }

    @Test
    fun `새 비밀번호가 현재와 같아도 허용된다`() {
        seed()

        change(new = "oldpassword1")

        assertEquals(PasswordHash("hashed:oldpassword1"), users.stored[1]!!.passwordHash)
    }

    @Test
    fun `현재 비밀번호가 틀리면 CURRENT_PASSWORD_MISMATCH 이고 실패가 기록되며 세션은 그대로다`() {
        seed()

        val e = assertFailsWith<CurrentPasswordMismatchException> { change(current = "wrongpassword") }

        assertEquals(UserErrorCode.CURRENT_PASSWORD_MISMATCH, e.errorCode)
        assertEquals(1, limiter.failures)
        assertEquals(PasswordHash("hashed:oldpassword1"), users.stored[1]!!.passwordHash)
        assertEquals("old-jti", sessions.currentAccessTokenId(UserId(1)))
    }

    @Test
    fun `현재 비밀번호 형식이 잘못돼도 불일치로 다룬다`() {
        seed()

        assertFailsWith<CurrentPasswordMismatchException> { change(current = "x") }

        assertEquals(1, limiter.failures)
    }

    @Test
    fun `실패가 임계에 닿으면 계정을 잠그고 세션을 끝낸다`() {
        seed()
        limiter.thresholdAt = 1

        val e = assertFailsWith<AccountLockedException> { change(current = "wrongpassword") }

        assertEquals(UserErrorCode.ACCOUNT_LOCKED, e.errorCode)
        assertTrue(users.stored[1]!!.isLocked)
        assertTrue(sessions.isAccessTokenBlacklisted("old-jti"))
        assertEquals(listOf(UserId(1)), realtime.closeAllOfCalls)
        assertNull(sessions.currentAccessTokenId(UserId(1)))
    }

    @Test
    fun `잠긴 계정은 올바른 비밀번호여도 ACCOUNT_LOCKED 이고 아무것도 바뀌지 않는다`() {
        seed(locked = true)

        assertFailsWith<AccountLockedException> { change() }

        assertEquals(PasswordHash("hashed:oldpassword1"), users.stored[1]!!.passwordHash)
        assertFalse(sessions.isAccessTokenBlacklisted("old-jti"))
    }

    @Test
    fun `새 비밀번호가 너무 짧으면 PASSWORD_TOO_SHORT 이고 실패로 세지 않는다`() {
        seed()

        val e = assertFailsWith<InvalidPasswordException> { change(new = "short") }

        assertEquals(UserErrorCode.PASSWORD_TOO_SHORT, e.errorCode)
        assertEquals(0, limiter.failures)
    }

    @Test
    fun `없는 사용자면 USER_NOT_FOUND 다`() {
        assertFailsWith<UserNotFoundException> { change() }
    }
}
