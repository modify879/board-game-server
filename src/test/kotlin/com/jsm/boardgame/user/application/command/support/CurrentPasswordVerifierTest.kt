package com.jsm.boardgame.user.application.command.support

import com.jsm.boardgame.user.application.command.usecase.LogoutUseCase
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.CurrentPasswordMismatchException
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
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
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class VerifierFakeUserRepository : UserRepository {
    override fun findByUsername(username: Username): User? = null
    override fun findById(id: UserId): User? = null
    override fun findByIdForUpdate(id: UserId): User? = null // 잠그려는 사이 사용자가 사라진 상황
    override fun existsByUsername(username: Username): Boolean = false
    override fun existsByNickname(nickname: Nickname): Boolean = false
    override fun save(user: User): User = user
}

private class VerifierFakeHasher : PasswordHasher {
    var matchesCalls = 0
    override fun hash(raw: RawPassword): PasswordHash = PasswordHash("hashed:${raw.value}")
    override fun matches(raw: RawPassword, hash: PasswordHash): Boolean {
        matchesCalls++
        return hash.value == "hashed:${raw.value}"
    }
}

private class VerifierFakeLimiter(private val trips: Boolean) : LoginAttemptLimiter {
    var failures = 0
    override fun recordFailure(username: Username, maxFailures: Int, window: Duration): Boolean {
        failures++
        return trips
    }
    override fun reset(username: Username) {}
}

private class VerifierFakeLogout : LogoutUseCase {
    val calls = mutableListOf<Long>()
    override fun logout(userId: Long) { calls += userId }
}

class CurrentPasswordVerifierTest {

    private val hasher = VerifierFakeHasher()
    private val logout = VerifierFakeLogout()

    private fun verifier(limiter: LoginAttemptLimiter) = CurrentPasswordVerifier(
        VerifierFakeUserRepository(), hasher, limiter, logout,
        5, Duration.ofMinutes(15), TransactionOperations.withoutTransaction(), Clock.systemUTC(),
    )

    private fun user(locked: Boolean = false) = User.reconstitute(
        id = UserId(1),
        username = Username.of("user_01"),
        passwordHash = PasswordHash("hashed:oldpassword1"),
        nickname = Nickname.of("길동이"),
        profileImageKey = null,
        role = UserRole.USER,
        lockedAt = if (locked) Instant.now() else null,
    )

    @Test
    fun `잠긴 사용자는 ACCOUNT_LOCKED 로 거부하고 해셔와 제한기를 건드리지 않는다`() {
        val limiter = VerifierFakeLimiter(trips = false)

        val e = assertFailsWith<AccountLockedException> {
            verifier(limiter).verify(user(locked = true), "oldpassword1")
        }

        assertEquals(UserErrorCode.ACCOUNT_LOCKED, e.errorCode)
        assertEquals(0, hasher.matchesCalls)
        assertEquals(0, limiter.failures)
    }

    @Test
    fun `틀린 비밀번호가 제한기를 넘겼는데 사용자가 사라졌으면 USER_NOT_FOUND 이고 세션은 끝내지 않는다`() {
        val limiter = VerifierFakeLimiter(trips = true)

        val e = assertFailsWith<UserNotFoundException> {
            verifier(limiter).verify(user(), "wrongpassword")
        }

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.errorCode)
        assertEquals(1, limiter.failures)
        assertTrue(logout.calls.isEmpty())
    }

    @Test
    fun `형식이 잘못된 현재 비밀번호도 틀린 비밀번호와 같이 실패로 기록하고 CURRENT_PASSWORD_MISMATCH 로 거부한다`() {
        val limiter = VerifierFakeLimiter(trips = false)

        val e = assertFailsWith<CurrentPasswordMismatchException> {
            verifier(limiter).verify(user(), "short")
        }

        assertEquals(UserErrorCode.CURRENT_PASSWORD_MISMATCH, e.errorCode)
        assertEquals(1, limiter.failures)
        assertEquals(0, hasher.matchesCalls)
    }
}
