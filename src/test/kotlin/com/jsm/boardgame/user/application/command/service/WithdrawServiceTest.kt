package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.support.CurrentPasswordVerifier
import com.jsm.boardgame.user.application.command.usecase.WithdrawCommand
import com.jsm.boardgame.common.error.CommonErrorCode
import com.jsm.boardgame.user.application.exception.CurrentPasswordMismatchException
import com.jsm.boardgame.user.application.exception.WalletLockContendedException
import com.jsm.boardgame.user.application.exception.WithdrawalSeatedAtTableException
import com.jsm.boardgame.user.application.exception.WithdrawalWalletNotEmptyException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.ProfileImageType
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class WithdrawFakeUserRepository : UserRepository {
    val stored = mutableMapOf<Long, User>()
    override fun findByUsername(username: Username): User? = stored.values.find { it.username == username }
    // 읽을 때마다 새 복사본을 준다 — 서비스가 락 뒤에 다시 읽는지 증명하려고
    override fun findById(id: UserId): User? = stored[id.value]?.let(::copyOf)
    override fun findByIdForUpdate(id: UserId): User? = findById(id)
    override fun existsByUsername(username: Username): Boolean = false
    override fun existsByNickname(nickname: Nickname): Boolean = false
    override fun save(user: User): User { stored[user.id!!.value] = user; return user }
}

private fun copyOf(u: User) = User.reconstitute(
    id = u.id!!, username = u.username, passwordHash = u.passwordHash, nickname = u.nickname,
    profileImageKey = u.profileImageKey, role = u.role, lockedAt = u.lockedAt, withdrawnAt = u.withdrawnAt,
)

private class WithdrawFakeHasher : PasswordHasher {
    override fun hash(raw: RawPassword) = PasswordHash("hashed:${raw.value}")
    override fun matches(raw: RawPassword, hash: PasswordHash) = hash.value == "hashed:${raw.value}"
}

private class WithdrawFakeLimiter : LoginAttemptLimiter {
    var failures = 0
    var resets = 0
    override fun recordFailure(username: Username, maxFailures: Int, window: Duration): Boolean { failures++; return false }
    override fun reset(username: Username) { resets++ }
}

private class WithdrawFakeRealtime : RealtimeConnections {
    val closeAllOfCalls = mutableListOf<UserId>()
    override fun closeAllOf(userId: UserId) { closeAllOfCalls += userId }
    override fun closeAfterGrace(accessTokenId: String) {}
}

private class WithdrawFakeSessions : AuthSessionStore {
    val sessions = mutableMapOf<UserId, AuthSession>()
    val blacklist = mutableSetOf<String>()
    override fun start(userId: UserId, session: AuthSession) { sessions[userId] = session }
    override fun currentAccessTokenId(userId: UserId): String? = sessions[userId]?.accessTokenId
    override fun clear(userId: UserId) { sessions.remove(userId) }
    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) { blacklist += accessTokenId }
    override fun isAccessTokenBlacklisted(accessTokenId: String) = accessTokenId in blacklist
    override fun rotate(userId: UserId, presentedRefreshToken: String, next: AuthSession, nextAccessToken: String, nextAccessTokenExpiresAt: Instant, nextRole: UserRole): RotationResult =
        throw UnsupportedOperationException()
    override fun userIdForRefreshToken(refreshToken: String): UserId? = null
}

private class WithdrawFakeStorage : ProfileImageStorage {
    val deleted = mutableListOf<String>()
    var failOnDelete = false
    override fun store(userId: UserId, bytes: ByteArray, type: ProfileImageType): ProfileImageKey = error("unused")
    override fun delete(key: ProfileImageKey) {
        if (failOnDelete) throw java.io.IOException("disk")
        deleted += key.value
    }
}

class WithdrawServiceTest {

    private val users = WithdrawFakeUserRepository()
    private val limiter = WithdrawFakeLimiter()
    private val realtime = WithdrawFakeRealtime()
    private val sessions = WithdrawFakeSessions()
    private val storage = WithdrawFakeStorage()
    private var seated = false
    private var walletBusy = false
    private val calls = mutableListOf<String>()
    private var contendedLocks = 0
    private var concurrentChange: () -> Unit = {}
    private val logout = LogoutService(sessions, realtime, Duration.ofMinutes(30), Clock.systemUTC())
    private val verifier = CurrentPasswordVerifier(users, WithdrawFakeHasher(), limiter, logout, 5, Duration.ofMinutes(15), TransactionOperations.withoutTransaction(), Clock.systemUTC())
    private val service = WithdrawService(
        users, verifier,
        { calls += "presence"; seated },
        {
            calls += "walletLock"
            concurrentChange()
            if (contendedLocks > 0) {
                contendedLocks--
                throw WalletLockContendedException("경합", IllegalStateException())
            }
            walletBusy
        },
        storage, logout, limiter, TransactionOperations.withoutTransaction(), Clock.systemUTC(),
    )

    private fun seed(imageKey: String? = null) {
        users.stored[1] = User.reconstitute(
            id = UserId(1),
            username = Username.of("user_01"),
            passwordHash = PasswordHash("hashed:password1"),
            nickname = Nickname.of("길동이"),
            profileImageKey = imageKey?.let { ProfileImageKey.of(it) },
            role = UserRole.USER,
        )
        sessions.start(UserId(1), AuthSession("jti-1", "rt-1", Instant.now().plusSeconds(1_000)))
    }

    @Test
    fun `성공하면 탈퇴 처리되고 이미지가 지워지며 세션이 끝나고 실패 카운터가 초기화된다`() {
        seed(imageKey = "1/a.png")

        service.withdraw(WithdrawCommand(1L, "password1"))

        val user = users.stored[1]!!
        assertNotNull(user.withdrawnAt)
        assertEquals("withdrawn-user-1", user.nickname.value)
        assertNull(user.profileImageKey)
        assertEquals(listOf("1/a.png"), storage.deleted)
        assertTrue(sessions.isAccessTokenBlacklisted("jti-1"))
        assertEquals(listOf(UserId(1)), realtime.closeAllOfCalls)
        assertNull(sessions.currentAccessTokenId(UserId(1)))
        assertEquals(1, limiter.resets)
    }

    @Test
    fun `락을 기다리는 사이 바뀐 프로필 이미지를 덮어쓰지 않고 바뀐 이미지를 지운다`() {
        seed(imageKey = "1/a.png")
        concurrentChange = {
            users.stored[1] = User.reconstitute(
                id = UserId(1), username = Username.of("user_01"), passwordHash = PasswordHash("hashed:password1"),
                nickname = Nickname.of("길동이"), profileImageKey = ProfileImageKey.of("1/b.png"), role = UserRole.USER,
            )
        }

        service.withdraw(WithdrawCommand(1L, "password1"))

        assertEquals(listOf("1/b.png"), storage.deleted)
        assertNull(users.stored[1]!!.profileImageKey)
    }

    @Test
    fun `이미지 삭제가 실패해도 탈퇴는 끝나고 세션도 끊긴다`() {
        seed(imageKey = "1/a.png")
        storage.failOnDelete = true

        service.withdraw(WithdrawCommand(1L, "password1"))

        assertNotNull(users.stored[1]!!.withdrawnAt)
        assertNull(sessions.currentAccessTokenId(UserId(1)))
    }

    @Test
    fun `비밀번호가 틀리면 CURRENT_PASSWORD_MISMATCH 이고 실패가 기록되며 아무것도 바뀌지 않는다`() {
        seed()

        val e = assertFailsWith<CurrentPasswordMismatchException> { service.withdraw(WithdrawCommand(1L, "wrongpass")) }

        assertEquals(UserErrorCode.CURRENT_PASSWORD_MISMATCH, e.errorCode)
        assertEquals(1, limiter.failures)
        assertNull(users.stored[1]!!.withdrawnAt)
        assertEquals("jti-1", sessions.currentAccessTokenId(UserId(1)))
    }

    @Test
    fun `확인한 뒤 행 락 전에 비밀번호가 바뀌었으면 CURRENT_PASSWORD_MISMATCH 이고 탈퇴되지 않으며 세션은 그대로다`() {
        seed()
        concurrentChange = {
            users.stored[1] = User.reconstitute(
                id = UserId(1), username = Username.of("user_01"), passwordHash = PasswordHash("hashed:otherpassword1"),
                nickname = Nickname.of("길동이"), profileImageKey = null, role = UserRole.USER,
            )
        }

        val e = assertFailsWith<CurrentPasswordMismatchException> { service.withdraw(WithdrawCommand(1L, "password1")) }

        assertEquals(UserErrorCode.CURRENT_PASSWORD_MISMATCH, e.errorCode)
        assertNull(users.stored[1]!!.withdrawnAt)
        assertEquals(PasswordHash("hashed:otherpassword1"), users.stored[1]!!.passwordHash)
        assertEquals("jti-1", sessions.currentAccessTokenId(UserId(1)))
        assertEquals(0, limiter.resets)
    }

    @Test
    fun `테이블에 앉아 있거나 대기 중이면 WITHDRAWAL_SEATED_AT_TABLE 이다`() {
        seed()
        seated = true

        val e = assertFailsWith<WithdrawalSeatedAtTableException> { service.withdraw(WithdrawCommand(1L, "password1")) }

        assertEquals(UserErrorCode.WITHDRAWAL_SEATED_AT_TABLE, e.errorCode)
        assertNull(users.stored[1]!!.withdrawnAt)
        assertEquals("jti-1", sessions.currentAccessTokenId(UserId(1)))
    }

    @Test
    fun `지갑에 잔액이나 대기 요청이 있으면 WITHDRAWAL_WALLET_NOT_EMPTY 다`() {
        seed()
        walletBusy = true

        val e = assertFailsWith<WithdrawalWalletNotEmptyException> { service.withdraw(WithdrawCommand(1L, "password1")) }

        assertEquals(UserErrorCode.WITHDRAWAL_WALLET_NOT_EMPTY, e.errorCode)
        assertNull(users.stored[1]!!.withdrawnAt)
    }

    @Test
    fun `지갑 락을 좌석 확인보다 먼저 잡는다`() {
        seed()

        service.withdraw(WithdrawCommand(1L, "password1"))

        assertEquals(listOf("walletLock", "presence"), calls)
    }

    @Test
    fun `좌석과 지갑이 둘 다 걸리면 WITHDRAWAL_SEATED_AT_TABLE 이 우선한다`() {
        seed()
        seated = true
        walletBusy = true

        val e = assertFailsWith<WithdrawalSeatedAtTableException> { service.withdraw(WithdrawCommand(1L, "password1")) }

        assertEquals(UserErrorCode.WITHDRAWAL_SEATED_AT_TABLE, e.errorCode)
    }

    @Test
    fun `지갑 락이 첫 지갑 생성과 한 번 엇갈리면 트랜잭션째 다시 해서 탈퇴된다`() {
        seed()
        contendedLocks = 1

        service.withdraw(WithdrawCommand(1L, "password1"))

        assertEquals(listOf("walletLock", "walletLock", "presence"), calls)
        assertNotNull(users.stored[1]!!.withdrawnAt)
        assertNull(sessions.currentAccessTokenId(UserId(1)))
    }

    @Test
    fun `두 번 엇갈리면 더 재시도하지 않고 TEMPORARILY_UNAVAILABLE 이 나가며 아무것도 안 바뀐다`() {
        seed()
        contendedLocks = 2

        val e = assertFailsWith<WalletLockContendedException> { service.withdraw(WithdrawCommand(1L, "password1")) }

        assertEquals(CommonErrorCode.TEMPORARILY_UNAVAILABLE, e.errorCode)
        assertEquals(listOf("walletLock", "walletLock"), calls)
        assertNull(users.stored[1]!!.withdrawnAt)
        assertEquals("jti-1", sessions.currentAccessTokenId(UserId(1)))
    }

    @Test
    fun `없는 사용자면 USER_NOT_FOUND 다`() {
        assertFailsWith<UserNotFoundException> { service.withdraw(WithdrawCommand(9L, "password1")) }
    }
}
