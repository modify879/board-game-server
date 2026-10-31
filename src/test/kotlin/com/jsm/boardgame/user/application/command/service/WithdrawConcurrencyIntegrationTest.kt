package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.user.application.command.usecase.WithdrawCommand
import com.jsm.boardgame.user.application.command.usecase.WithdrawUseCase
import com.jsm.boardgame.user.application.port.WalletHoldings
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameUseCase
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 탈퇴와 돈의 이동이 지갑 락 하나로 직렬화되는지 실제 Postgres 로 본다.
 * 불변식: 탈퇴했으면 좌석도, 지갑 잔액도, 대기 중인 충전 요청도 없다.
 */
@IntegrationTest
class WithdrawConcurrencyIntegrationTest {

    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var hasher: PasswordHasher
    @Autowired private lateinit var withdraw: WithdrawUseCase
    @Autowired private lateinit var walletHoldings: WalletHoldings
    @Autowired private lateinit var admit: AdmitJoinRequestUseCase
    @Autowired private lateinit var transferToGame: TransferToGameUseCase
    @Autowired private lateinit var adjustWallet: AdjustWalletBalanceUseCase
    @Autowired private lateinit var requestDeposit: RequestDepositUseCase
    @Autowired private lateinit var tables: HoldemTableRepository
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val password = "password123"
    private val buyIn = 10_000L

    @Test
    fun `탈퇴와 같은 사용자의 착석이 동시에 들어와도 앉은 채 탈퇴되지 않는다`() {
        repeat(20) { i ->
            val userId = newUser()
            adjustWallet.adjust(AdjustWalletBalanceCommand(userId, buyIn, "테스트 지급", 1L, UUID.randomUUID().toString()))
            val tableId = tables.save(HoldemTable.create("withdraw-race-${UniqueNames.suffix()}", TestTablePolicy)).id!!.value

            // 탈퇴는 비밀번호 해싱 뒤에 락을 잡으므로 착석 출발을 조금씩 늦춰 두 순서를 모두 훑는다.
            val (withdrawResult, admitResult) = race(
                { withdraw.withdraw(WithdrawCommand(userId, password)) },
                { Thread.sleep(i * 5L); admit.admit(AdmitJoinRequestCommand(tableId, userId, false, buyIn)) },
            )

            withdrawResult.exceptionOrNull()?.let { assertBusiness(it) }
            admitResult.exceptionOrNull()?.let { assertBusiness(it) }
            assertInvariant(userId)
        }
    }

    @Test
    fun `탈퇴와 같은 사용자의 충전 요청이 동시에 들어와도 탈퇴자에게 대기 요청이 남지 않는다`() {
        repeat(20) { i ->
            val userId = newUser()

            val (withdrawResult, depositResult) = race(
                { withdraw.withdraw(WithdrawCommand(userId, password)) },
                { Thread.sleep(i * 5L); requestDeposit.request(RequestDepositCommand(userId, 5_000, UUID.randomUUID().toString())) },
            )

            withdrawResult.exceptionOrNull()?.let { assertBusiness(it) }
            depositResult.exceptionOrNull()?.let { assertBusiness(it) }
            assertInvariant(userId)
        }
    }

    @Test
    fun `탈퇴 트랜잭션이 지갑 락을 쥔 동안 들어온 바이인은 기다렸다가 WALLET_OWNER_NOT_FOUND 로 떨어진다`() {
        val userId = newUser()
        adjustWallet.adjust(AdjustWalletBalanceCommand(userId, buyIn, "테스트 지급", 1L, UUID.randomUUID().toString()))

        // WithdrawService 의 트랜잭션을 그대로 흉내 낸다(지갑 락 → 탈퇴 저장). 잔액 확인 결과는 무시한다 —
        // 여기서 보려는 건 락을 기다린 쪽이 활성 확인에서 떨어지는 경로다.
        val pool = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1)
        val commit = CountDownLatch(1)
        try {
            val closure = pool.submit {
                tx.executeWithoutResult {
                    walletHoldings.lockAndHasBalanceOrPendingRequests(UserId(userId))
                    val user = users.findById(UserId(userId))!!
                    user.withdraw(Instant.now())
                    users.save(user)
                    locked.countDown()
                    commit.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val transfer = pool.submit<Result<Unit>> {
                runCatching { transferToGame.transfer(TransferToGameCommand(userId, buyIn, 1L, "holdem")) }
            }
            try {
                awaitLockWait()
            } finally {
                commit.countDown()
            }
            closure.get(10, TimeUnit.SECONDS)

            val e = transfer.get(10, TimeUnit.SECONDS).exceptionOrNull() as? BusinessException ?: fail("바이인이 성공했다")
            assertEquals(WalletErrorCode.WALLET_OWNER_NOT_FOUND, e.errorCode)
            assertEquals(buyIn, balance(userId))
        } finally {
            pool.shutdown()
        }
    }

    private fun newUser(): Long {
        val suffix = UniqueNames.suffix()
        return users.save(
            User.register(Username.of("u$suffix"), hasher.hash(RawPassword.of(password)), Nickname.of("n$suffix")),
        ).id!!.value
    }

    private fun race(a: () -> Unit, b: () -> Unit): Pair<Result<Unit>, Result<Unit>> {
        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        try {
            val futures = listOf(a, b).map { f ->
                pool.submit<Result<Unit>> {
                    go.await(10, TimeUnit.SECONDS)
                    runCatching { f() }
                }
            }
            go.countDown()
            return futures[0].get(30, TimeUnit.SECONDS) to futures[1].get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdown()
        }
    }

    /** 예상한 거절(코드 있는 예외)만 허용한다 — 데드락·타임아웃 같은 예기치 못한 실패는 테스트를 깬다. */
    private fun assertBusiness(e: Throwable) {
        if (e !is BusinessException) throw AssertionError("예기치 못한 실패", e)
    }

    private fun assertInvariant(userId: Long) {
        val withdrawn = jdbc.queryForObject("select withdrawn_at is not null from users where id = ?", Boolean::class.java, userId)!!
        if (!withdrawn) return
        val seats = jdbc.queryForObject("select count(*) from holdem_seats where user_id = ?", Int::class.java, userId)!!
        val pending = jdbc.queryForObject(
            "select count(*) from deposit_requests where user_id = ? and status = 'PENDING'", Int::class.java, userId,
        )!!
        assertEquals(0, seats, "탈퇴했는데 앉아 있다: userId=$userId")
        assertEquals(0L, balance(userId), "탈퇴했는데 잔액이 있다: userId=$userId")
        assertEquals(0, pending, "탈퇴했는데 대기 충전 요청이 있다: userId=$userId")
    }

    private fun balance(userId: Long): Long =
        jdbc.queryForObject("select coalesce(max(balance), 0) from wallets where user_id = ?", Long::class.java, userId)!!

    /** 다른 세션이 락 대기에 들어갈 때까지 폴링한다. lock_timeout(3s) 보다 앞서 풀도록 2초로 묶는다. */
    private fun awaitLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                Long::class.java,
            ) == 0L
        ) {
            check(System.nanoTime() < deadline) { "바이인이 지갑 락 대기에 들어가지 않았다" }
            Thread.sleep(10)
        }
    }
}
