package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsUseCase
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestCommand
import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.UserId as WalletUserId
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 충전 승인 → WalletCredited → 커밋 후 리스너 → TableExecutor → 스택 합산까지 실제 배선으로 검증한다.
 * 핸드가 없는 테이블에 앉아 있는 사용자의 충전분이 그 자리에서 DB 좌석 스택으로 옮겨져야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TopUpSeatFromWalletIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tables: HoldemTableRepository

    @Autowired
    private lateinit var wallets: WalletRepository

    @Autowired
    private lateinit var requestDeposit: RequestDepositUseCase

    @Autowired
    private lateinit var approveDeposit: ApproveDepositRequestUseCase

    @Autowired
    private lateinit var topUpTableSeats: TopUpTableSeatsUseCase

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    /** 합산은 테이블 스레드에서 비동기로 돈다 — 조건이 될 때까지 잠깐 기다린다. */
    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    /** fk_wallets_user 때문에 실제 users 행을 먼저 만든다. */
    private fun signUp(): Long {
        val suffix = UniqueNames.suffix()
        val username = "u" + suffix
        val result = mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"username":"$username","password":"password123","passwordConfirm":"password123","nickname":"n${suffix}"}""",
                ),
        ).andExpect(status().isCreated).andReturn()
        return result.response.getHeader("Location")!!.substringAfterLast("/").toLong()
    }

    @Test
    fun `핸드가 없는 테이블에 앉아 있으면 충전 승인 즉시 지갑 잔액 전부가 좌석 스택에 합쳐진다`() {
        val userId = signUp()
        var table = tables.save(HoldemTable.create("topup-${UUID.randomUUID().toString().take(8)}"))
        table.sitDown(UserId(userId), Chips.of(10_000))
        table = tables.save(table)

        val requestId = requestDeposit.request(RequestDepositCommand(userId, 10_000))
        approveDeposit.approve(ApproveDepositRequestCommand(requestId.value, adminUserId = userId, creditedAmount = null))

        awaitTrue { tables.findById(table.id!!)!!.seatOf(UserId(userId))!!.stack == Chips.of(20_000) }
        assertThat(tables.findById(table.id!!)!!.seatOf(UserId(userId))!!.stack).isEqualTo(Chips.of(20_000))
        assertThat(wallets.findByUserId(WalletUserId(userId))!!.balance).isEqualTo(Money.ZERO)
    }

    @Test
    fun `다른 트랜잭션이 지갑 행을 잡고 있으면 합산은 예외 없이 건너뛰고 스택을 지킨다`() {
        val userId = signUp()
        // 앉기 전에 충전을 승인해 잔액이 지갑에 남게 한다(앉아 있었다면 승인 즉시 합쳐진다).
        val requestId = requestDeposit.request(RequestDepositCommand(userId, 5_000))
        approveDeposit.approve(ApproveDepositRequestCommand(requestId.value, adminUserId = userId, creditedAmount = null))
        var table = tables.save(HoldemTable.create("topup-lock-${UUID.randomUUID().toString().take(8)}"))
        table.sitDown(UserId(userId), Chips.of(10_000))
        table = tables.save(table)

        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            transactionTemplate.executeWithoutResult {
                jdbc.queryForList("select id from wallets where user_id = ? for update", userId)
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()

            topUpTableSeats.topUp(TopUpTableSeatsCommand(table.id!!.value)) // lock_timeout 으로 실패해도 던지지 않는다

            assertThat(tables.findById(table.id)!!.seatOf(UserId(userId))!!.stack).isEqualTo(Chips.of(10_000))
        } finally {
            release.countDown()
            holder.join()
        }

        topUpTableSeats.topUp(TopUpTableSeatsCommand(table.id.value)) // 락이 풀리면 다음 합산에서 합쳐진다
        assertThat(tables.findById(table.id)!!.seatOf(UserId(userId))!!.stack).isEqualTo(Chips.of(15_000))
    }
}
