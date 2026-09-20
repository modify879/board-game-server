package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleCommand
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.exception.InvalidUserRoleException
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
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
import kotlin.test.assertTrue

private class ChangeRoleFakeAuthSessionStore : AuthSessionStore {
    private val sessions = mutableMapOf<Long, AuthSession>()
    private val blacklisted = mutableSetOf<String>()
    var clearCalled = false
        private set

    override fun start(userId: Long, session: AuthSession) {
        sessions[userId] = session
    }

    override fun currentAccessTokenId(userId: Long): String? = sessions[userId]?.accessTokenId

    override fun clear(userId: Long) {
        clearCalled = true
        sessions.remove(userId)
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        blacklisted += accessTokenId
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = accessTokenId in blacklisted

    override fun rotate(userId: Long, presentedRefreshToken: String, next: AuthSession): RotationResult =
        RotationResult.Mismatch

    override fun userIdForRefreshToken(refreshToken: String): Long? = null
}

private class ChangeRoleFakeUserRepository : UserRepository {
    private val stored = mutableMapOf<Long, User>()

    fun put(userId: Long, role: UserRole = UserRole.USER) {
        stored[userId] = User.reconstitute(
            id = UserId(userId),
            username = Username.reconstitute("user_$userId"),
            passwordHash = PasswordHash("hashed"),
            nickname = Nickname.reconstitute("닉네임$userId"),
            profileImageKey = null,
            role = role,
        )
    }

    override fun findByUsername(username: Username): User? = stored.values.find { it.username == username }
    override fun findById(id: UserId): User? = stored[id.value]
    override fun existsByUsername(username: Username): Boolean = stored.values.any { it.username == username }
    override fun existsByNickname(nickname: Nickname): Boolean = stored.values.any { it.nickname == nickname }
    override fun save(user: User): User {
        stored[user.id!!.value] = user
        return user
    }
}

class ChangeUserRoleServiceTest {

    private val sessions = ChangeRoleFakeAuthSessionStore()
    private val users = ChangeRoleFakeUserRepository()
    private val service = ChangeUserRoleService(users, sessions, Duration.ofMinutes(30), Clock.systemUTC())

    @Test
    fun `대상 사용자의 역할이 바뀌고 저장된다`() {
        users.put(1L, UserRole.USER)

        service.changeRole(ChangeUserRoleCommand(1L, "ADMIN"))

        assertEquals(UserRole.ADMIN, users.findById(UserId(1L))!!.role)
    }

    @Test
    fun `대상의 현재 액세스 토큰이 블랙리스트에 들어간다`() {
        users.put(1L, UserRole.ADMIN)
        sessions.start(1L, AuthSession("jti-1", "refresh-1", Instant.now().plusSeconds(1_000)))

        service.changeRole(ChangeUserRoleCommand(1L, "USER"))

        assertTrue(sessions.isAccessTokenBlacklisted("jti-1"))
    }

    @Test
    fun `세션이 없는 사용자도 예외 없이 역할만 바뀐다`() {
        users.put(1L, UserRole.USER)

        service.changeRole(ChangeUserRoleCommand(1L, "ADMIN"))

        assertEquals(UserRole.ADMIN, users.findById(UserId(1L))!!.role)
    }

    @Test
    fun `없는 사용자면 USER_NOT_FOUND`() {
        val e = assertFailsWith<UserNotFoundException> {
            service.changeRole(ChangeUserRoleCommand(999L, "ADMIN"))
        }

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `리프레시 토큰 세션은 폐기되지 않는다`() {
        users.put(1L, UserRole.ADMIN)
        sessions.start(1L, AuthSession("jti-1", "refresh-1", Instant.now().plusSeconds(1_000)))

        service.changeRole(ChangeUserRoleCommand(1L, "USER"))

        assertFalse(sessions.clearCalled)
    }

    @Test
    fun `알 수 없는 역할 문자열이면 USER_ROLE_INVALID`() {
        users.put(1L, UserRole.USER)

        val e = assertFailsWith<InvalidUserRoleException> {
            service.changeRole(ChangeUserRoleCommand(1L, "SUPER_ADMIN"))
        }

        assertEquals(UserErrorCode.USER_ROLE_INVALID, e.errorCode)
        assertEquals(UserRole.USER, users.findById(UserId(1L))!!.role)
    }

    @Test
    fun `역할 검사가 사용자 조회보다 먼저다 - 없는 사용자에 잘못된 역할이면 USER_ROLE_INVALID`() {
        val e = assertFailsWith<InvalidUserRoleException> {
            service.changeRole(ChangeUserRoleCommand(999L, "nope"))
        }

        assertEquals(UserErrorCode.USER_ROLE_INVALID, e.errorCode)
    }

    @Test
    fun `소문자와 공백은 정규화되어 통과한다`() {
        users.put(1L, UserRole.USER)

        service.changeRole(ChangeUserRoleCommand(1L, " admin "))

        assertEquals(UserRole.ADMIN, users.findById(UserId(1L))!!.role)
    }
}
