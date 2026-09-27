package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestCommand
import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.domain.exception.DepositRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 지갑·입금 요청 행의 `PESSIMISTIC_WRITE` 잠금이 실제 동시 요청에서 lost update 를
 * 막는지 검증한다. 인메모리 페이크는 행 잠금을 흉내낼 수 없으므로, 이 시나리오는
 * 실제 Postgres 를 켠 통합 테스트로만 증명할 수 있다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WalletPessimisticLockIntegrationTest {

    @Autowired
    private lateinit var adjustWalletBalance: AdjustWalletBalanceUseCase

    @Autowired
    private lateinit var requestDeposit: RequestDepositUseCase

    @Autowired
    private lateinit var approveDepositRequest: ApproveDepositRequestUseCase

    @Autowired
    private lateinit var wallets: WalletRepository

    @Autowired
    private lateinit var users: UserRepository

    @Test
    fun `두 개의 동시 지급이 모두 반영된다`() {
        val userId = uniqueUserId(users)
        // 지갑을 미리 열어 둔다 — 둘 다 지갑이 없는 채로 동시에 들어오면 findOrOpen 이 각자
        // Wallet.open 을 시도해 uk_wallets_user 위반으로 떨어진다(WalletRepository 문서 참조).
        // 이 테스트가 보려는 건 그 경합이 아니라 이미 있는 지갑 행의 PESSIMISTIC_WRITE 잠금이다.
        wallets.findOrOpen(userId)
        val keyBase = UUID.randomUUID().toString()
        val executor = Executors.newFixedThreadPool(2)
        val readyLatch = CountDownLatch(2)
        val goLatch = CountDownLatch(1)

        try {
            val futures = listOf("-1", "-2").map { suffix ->
                executor.submit<Unit> {
                    readyLatch.countDown()
                    goLatch.await(10, TimeUnit.SECONDS)
                    adjustWalletBalance.adjust(
                        AdjustWalletBalanceCommand(
                            targetUserId = userId,
                            amount = 1000L,
                            reason = "동시 지급 테스트",
                            adminUserId = 1L,
                            idempotencyKey = keyBase + suffix,
                        ),
                    )
                }
            }

            readyLatch.await(10, TimeUnit.SECONDS)
            goLatch.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(Money.of(2000L), wallets.findByUserId(userId)!!.balance)
    }

    @Test
    fun `같은 입금 요청을 동시에 두 번 승인하면 하나만 성공하고 잔액은 한 번만 반영된다`() {
        val userId = uniqueUserId(users)
        val requestId = requestDeposit.request(RequestDepositCommand(userId = userId, amount = 10_000L)).value
        val executor = Executors.newFixedThreadPool(2)
        val readyLatch = CountDownLatch(2)
        val goLatch = CountDownLatch(1)

        val results = try {
            val futures = (1..2).map {
                executor.submit<Result<Unit>> {
                    readyLatch.countDown()
                    goLatch.await(10, TimeUnit.SECONDS)
                    runCatching {
                        approveDepositRequest.approve(
                            ApproveDepositRequestCommand(requestId = requestId, adminUserId = 1L, creditedAmount = null),
                        )
                    }
                }
            }

            readyLatch.await(10, TimeUnit.SECONDS)
            goLatch.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        val successes = results.filter { it.isSuccess }
        val failures = results.filter { it.isFailure }
        assertEquals(1, successes.size)
        assertEquals(1, failures.size)
        val failure = assertIs<DepositRequestAlreadyProcessedException>(failures.single().exceptionOrNull())
        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, failure.errorCode)

        assertEquals(Money.of(10_000L), wallets.findByUserId(userId)!!.balance)
    }
}

private fun uniqueUserId(users: UserRepository): Long {
    val suffix = UUID.randomUUID().toString().replace("-", "").take(9).lowercase()
    val user = User.register(
        username = Username.of("u$suffix"),
        passwordHash = PasswordHash("hashed-password-value"),
        nickname = Nickname.of("n" + suffix.take(5)),
    )
    return users.save(user).id!!.value
}
