package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.UnlockUserCommand
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class UnlockFakeUserRepository : UserRepository {
    private val stored = mutableMapOf<Long, User>()

    fun put(userId: Long, lockedAt: Instant?) {
        stored[userId] = User.reconstitute(
            id = UserId(userId),
            username = Username.reconstitute("user_$userId"),
            passwordHash = PasswordHash("hashed"),
            nickname = Nickname.reconstitute("닉네임$userId"),
            profileImageKey = null,
            role = UserRole.USER,
            lockedAt = lockedAt,
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

private class UnlockFakeLoginAttemptLimiter : LoginAttemptLimiter {
    val resets = mutableListOf<Username>()
    override fun recordFailure(username: Username, maxFailures: Int, window: Duration): Boolean = false
    override fun reset(username: Username) { resets += username }
}

class UnlockUserServiceTest {

    private val users = UnlockFakeUserRepository()
    private val limiter = UnlockFakeLoginAttemptLimiter()
    private val service = UnlockUserService(users, limiter)

    @Test
    fun `잠긴 사용자를 해제하면 잠금이 풀리고 저장된다`() {
        users.put(1L, Instant.parse("2026-01-01T00:00:00Z"))

        service.unlock(UnlockUserCommand(1L))

        val saved = users.findById(UserId(1L))!!
        assertFalse(saved.isLocked)
        assertNull(saved.lockedAt)
    }

    @Test
    fun `해제하면 로그인 실패 카운터도 초기화된다`() {
        users.put(1L, Instant.parse("2026-01-01T00:00:00Z"))

        service.unlock(UnlockUserCommand(1L))

        assertEquals(listOf(Username.reconstitute("user_1")), limiter.resets)
    }

    @Test
    fun `잠기지 않은 사용자를 해제해도 예외 없이 카운터만 초기화된다`() {
        users.put(1L, null)

        service.unlock(UnlockUserCommand(1L))

        assertFalse(users.findById(UserId(1L))!!.isLocked)
        assertTrue(limiter.resets.isNotEmpty())
    }

    @Test
    fun `없는 사용자면 USER_NOT_FOUND 이고 카운터는 건드리지 않는다`() {
        val e = assertFailsWith<UserNotFoundException> { service.unlock(UnlockUserCommand(999L)) }

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.errorCode)
        assertTrue(limiter.resets.isEmpty())
    }
}
