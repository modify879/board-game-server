package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalUseCase
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 같은 키의 동시 요청은 사전 조회를 둘 다 통과하므로, 실제 보장은 요청 테이블의
 * (user_id, idempotency_key) 유니크 제약이다. 첫 트랜잭션을 래치로 열어 둔 채 두 번째를 INSERT 에서
 * 막히게 한 뒤 커밋시켜, 결과를 결정적으로 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RequestIdempotencyConcurrencyIntegrationTest {

    @Autowired
    private lateinit var requestDeposit: RequestDepositUseCase

    @Autowired
    private lateinit var requestWithdrawal: RequestWithdrawalUseCase

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var wallets: WalletRepository

    @Autowired
    private lateinit var ledger: LedgerEntryRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private fun newUserId(): Long {
        val suffix = UniqueNames.suffix()
        val user = User.register(Username.of("u$suffix"), PasswordHash("hashed-password-value"), Nickname.of("n$suffix"))
        return users.save(user).id!!.value
    }

    private fun count(table: String, userId: Long): Int =
        jdbcTemplate.queryForObject("select count(*) from $table where user_id = ?", Int::class.java, userId)!!

    /** [first] 를 열린 트랜잭션 안에서 실행해 둔 채 [second] 를 시작하고, 막힌 것을 확인한 뒤 [first] 를 커밋한다. */
    private fun <T> raceWithOpenFirstTransaction(first: () -> T, second: () -> T): Pair<Result<T>, Result<T>> {
        val executor = Executors.newFixedThreadPool(2)
        val firstInserted = CountDownLatch(1)
        val commitFirst = CountDownLatch(1)
        try {
            val firstResult = executor.submit<Result<T>> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        val value = first()
                        firstInserted.countDown()
                        commitFirst.await(10, TimeUnit.SECONDS)
                        value
                    }!!
                }
            }
            firstInserted.await(10, TimeUnit.SECONDS)
            val secondResult = executor.submit<Result<T>> { runCatching { second() } }
            // 두 번째가 INSERT 의 유니크 대기(Lock)에 들어갈 때까지 폴링한다. lock_timeout(3s) 보다 앞서 풀도록 2초로 묶는다.
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (jdbcTemplate.queryForObject(
                        "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                        Long::class.java,
                    ) == 0L
                ) {
                    check(System.nanoTime() < deadline) { "두 번째 요청이 Lock 대기에 들어가지 않았다" }
                    Thread.sleep(10)
                }
            } finally {
                commitFirst.countDown()
            }
            return firstResult.get(10, TimeUnit.SECONDS) to secondResult.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `같은 키의 동시 충전 요청은 한 행만 만들고 나중 요청은 409 IDEMPOTENCY_KEY_CONFLICT 를 받으며 재시도하면 원래 결과를 받는다`() {
        val userId = newUserId()
        val command = RequestDepositCommand(userId = userId, amount = 5_000L, idempotencyKey = UUID.randomUUID().toString())

        val (first, second) = raceWithOpenFirstTransaction(
            first = { requestDeposit.request(command) },
            second = { requestDeposit.request(command) },
        )

        val created = first.getOrThrow()
        val e = assertFailsWith<BusinessException> { second.getOrThrow() }
        assertEquals(WalletErrorCode.IDEMPOTENCY_KEY_CONFLICT, e.errorCode)
        assertEquals(1, count("deposit_requests", userId))
        assertEquals(created, requestDeposit.request(command))
    }

    @Test
    fun `같은 키의 동시 환전 요청은 한 번만 차감되고 나중 요청은 409 IDEMPOTENCY_KEY_CONFLICT 를 받는다`() {
        val userId = newUserId()
        val wallet = wallets.findOrOpen(UserId(userId))
        val credit = wallet.record(
            type = LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT,
            amount = Money.of(10_000L),
            reference = LedgerReference(LedgerReferenceType.ADMIN_ADJUSTMENT, 0),
            at = Instant.now(),
        )
        wallets.save(wallet)
        ledger.save(credit)
        val command = RequestWithdrawalCommand(
            userId = userId, amount = 4_000L, bankName = "국민은행", accountNumber = "11122233344",
            accountHolder = "홍길동", idempotencyKey = UUID.randomUUID().toString(),
        )

        val (first, second) = raceWithOpenFirstTransaction(
            first = { requestWithdrawal.request(command) },
            second = { requestWithdrawal.request(command) },
        )

        val created = first.getOrThrow()
        val e = assertFailsWith<BusinessException> { second.getOrThrow() }
        assertEquals(WalletErrorCode.IDEMPOTENCY_KEY_CONFLICT, e.errorCode)
        assertEquals(1, count("withdrawal_requests", userId))
        assertEquals(Money.of(6_000L), wallets.findByUserId(UserId(userId))!!.balance)
        assertEquals(created, requestWithdrawal.request(command))
        assertEquals(Money.of(6_000L), wallets.findByUserId(UserId(userId))!!.balance)
    }
}
