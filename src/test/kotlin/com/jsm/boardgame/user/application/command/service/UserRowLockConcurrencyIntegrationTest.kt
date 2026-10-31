package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.user.application.command.support.CurrentPasswordVerifier
import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameCommand
import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameUseCase
import com.jsm.boardgame.user.application.command.usecase.ChangePasswordCommand
import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleCommand
import com.jsm.boardgame.user.application.command.usecase.LoginCommand
import com.jsm.boardgame.user.application.command.usecase.LoginUseCase
import com.jsm.boardgame.user.application.command.usecase.LogoutUseCase
import com.jsm.boardgame.user.application.command.usecase.WithdrawCommand
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.application.port.TablePresence
import com.jsm.boardgame.user.application.port.WalletHoldings
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** 쥔 쪽 서비스의 `save` 에서 멈춘다 — 그 시점에 서비스 트랜잭션은 사용자 행 락을 쥐고 있다. */
private class RowLockHoldingUsers(private val inner: UserRepository) : UserRepository by inner {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun save(user: User): User {
        held.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "release 를 기다리다 시간 초과" }
        return inner.save(user)
    }
}

/**
 * 같은 사용자를 바꾸는 두 명령이 겹쳐도 한쪽 변경이 사라지지 않는지 실제 Postgres 로 본다.
 * 한쪽 서비스를 사용자 행 락을 쥔 채(`save` 직전) 세우고, 다른 쪽이 그 락을 기다리는 것을 `pg_stat_activity` 로 확인한 뒤 놓는다.
 * 락 없이 "읽고 고쳐 전체 행 저장" 하던 때는 다른 쪽이 기다리지 않고 낡은 행을 읽어 덮어썼다.
 */
@IntegrationTest
class UserRowLockConcurrencyIntegrationTest {

    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var hasher: PasswordHasher
    @Autowired private lateinit var login: LoginUseCase
    @Autowired private lateinit var changeNickname: ChangeNicknameUseCase
    @Autowired private lateinit var limiter: LoginAttemptLimiter
    @Autowired private lateinit var tokenIssuer: AuthTokenIssuer
    @Autowired private lateinit var sessions: AuthSessionStore
    @Autowired private lateinit var realtime: RealtimeConnections
    @Autowired private lateinit var verifier: CurrentPasswordVerifier
    @Autowired private lateinit var tablePresence: TablePresence
    @Autowired private lateinit var walletHoldings: WalletHoldings
    @Autowired private lateinit var profileImageStorage: ProfileImageStorage
    @Autowired private lateinit var logout: LogoutUseCase
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var clock: Clock
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Value("\${app.auth.login-max-failures}") private var maxFailures: Int = 0
    @Value("\${app.auth.login-failure-window}") private lateinit var failureWindow: Duration

    private val password = "password123"
    private val ttl = Duration.ofMinutes(30)

    @Test
    fun `비밀번호 변경 중 로그인 실패로 계정이 잠겨도 새 비밀번호와 잠금이 둘 다 남는다`() {
        val user = newUser()
        repeat(maxFailures - 1) { limiter.recordFailure(user.username, maxFailures, failureWindow) } // 다음 실패가 임계
        val holding = RowLockHoldingUsers(users)
        val changePassword = ChangePasswordService(holding, hasher, tokenIssuer, sessions, realtime, limiter, verifier, ttl, tx, clock)

        val (changed, loggedIn) = holdThenRace(
            holding,
            { changePassword.changePassword(ChangePasswordCommand(user.id!!.value, password, "newpassword1")) },
            { login.login(LoginCommand(user.username.value, "wrongpassword1")) },
        )

        changed.getOrThrow()
        assertErrorCode(UserErrorCode.ACCOUNT_LOCKED, loggedIn)
        val (hash, locked) = jdbc.queryForMap("select password_hash, locked_at is not null as locked from users where id = ?", user.id!!.value)
            .let { it["password_hash"] as String to it["locked"] as Boolean }
        assertTrue(hasher.matches(RawPassword.of("newpassword1"), PasswordHash(hash)), "새 비밀번호가 사라졌다")
        assertTrue(locked, "잠금이 사라졌다")
    }

    @Test
    fun `탈퇴가 행 락을 쥔 동안 들어온 닉네임 변경은 기다렸다가 USER_NOT_FOUND 로 떨어지고 탈퇴가 되살아나지 않는다`() {
        val user = newUser()
        val holding = RowLockHoldingUsers(users)
        val withdraw = WithdrawService(holding, verifier, tablePresence, walletHoldings, profileImageStorage, logout, limiter, tx, clock)

        val (withdrawn, renamed) = holdThenRace(
            holding,
            { withdraw.withdraw(WithdrawCommand(user.id!!.value, password)) },
            { changeNickname.changeNickname(ChangeNicknameCommand(user.id!!.value, "r" + UniqueNames.suffix())) },
        )

        withdrawn.getOrThrow()
        assertErrorCode(UserErrorCode.USER_NOT_FOUND, renamed)
        val row = jdbc.queryForMap("select withdrawn_at, nickname from users where id = ?", user.id!!.value)
        assertNotNull(row["withdrawn_at"], "탈퇴가 되살아났다")
        assertEquals("withdrawn-user-${user.id.value}", row["nickname"])
    }

    @Test
    fun `역할 변경 중 닉네임이 바뀌어도 둘 다 남는다`() {
        val user = newUser()
        val holding = RowLockHoldingUsers(users)
        val changeRole = ChangeUserRoleService(holding, sessions, realtime, ttl, tx, clock)
        val nickname = "r" + UniqueNames.suffix()

        val (roleChanged, renamed) = holdThenRace(
            holding,
            { changeRole.changeRole(ChangeUserRoleCommand(user.id!!.value, "ADMIN")) },
            { changeNickname.changeNickname(ChangeNicknameCommand(user.id!!.value, nickname)) },
        )

        roleChanged.getOrThrow()
        renamed.getOrThrow()
        val row = jdbc.queryForMap("select role, nickname from users where id = ?", user.id!!.value)
        assertEquals("ADMIN", row["role"])
        assertEquals(nickname, row["nickname"])
    }

    private fun newUser(): User {
        val suffix = UniqueNames.suffix()
        return users.save(User.register(Username.of("u$suffix"), hasher.hash(RawPassword.of(password)), Nickname.of("n$suffix")))
    }

    /** [first] 가 행 락을 쥔 채 멈추면 [second] 를 출발시키고, [second] 가 락 대기에 들어간 걸 본 뒤 [first] 를 놓는다. */
    private fun holdThenRace(holding: RowLockHoldingUsers, first: () -> Unit, second: () -> Unit): Pair<Result<Unit>, Result<Unit>> {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Result<Unit>> { runCatching(first) }
            assertTrue(holding.held.await(10, TimeUnit.SECONDS), "첫 명령이 행 락을 쥔 채 멈추지 않았다")
            val b = pool.submit<Result<Unit>> { runCatching(second) }
            try {
                awaitUserRowLockWait()
            } finally {
                holding.release.countDown()
            }
            return a.get(10, TimeUnit.SECONDS) to b.get(10, TimeUnit.SECONDS)
        } finally {
            holding.release.countDown()
            pool.shutdown()
        }
    }

    /** 다른 세션이 users 행 락 대기에 들어갈 때까지 폴링한다. lock_timeout(3s) 보다 앞서 풀도록 2초로 묶는다. */
    private fun awaitUserRowLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and query ilike '%from users%'",
                Long::class.java,
            ) == 0L
        ) {
            check(System.nanoTime() < deadline) { "두 번째 명령이 사용자 행 락 대기에 들어가지 않았다" }
            Thread.sleep(10)
        }
    }

    private fun assertErrorCode(expected: UserErrorCode, result: Result<Unit>) {
        val e = result.exceptionOrNull() ?: fail("성공했다 — $expected 를 기대했다")
        if (e !is BusinessException) throw AssertionError("예기치 못한 실패", e)
        assertEquals(expected, e.errorCode)
    }
}
