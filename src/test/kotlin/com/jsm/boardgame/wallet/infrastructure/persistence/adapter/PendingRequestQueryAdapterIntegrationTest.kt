package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.wallet.application.query.port.DepositRequestQueryRepository
import com.jsm.boardgame.wallet.application.query.port.WithdrawalRequestQueryRepository
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionTemplate

/** 탈퇴 사전 조건이 쓰는 `existsPendingByUserId` 가 PENDING 만 센다. */
@IntegrationTest
class PendingRequestQueryAdapterIntegrationTest {

    @Autowired
    private lateinit var tx: TransactionTemplate

    @Autowired private lateinit var depositRequests: DepositRequestRepository
    @Autowired private lateinit var withdrawalRequests: WithdrawalRequestRepository
    @Autowired private lateinit var depositQuery: DepositRequestQueryRepository
    @Autowired private lateinit var withdrawalQuery: WithdrawalRequestQueryRepository

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `대기 중인 충전 요청은 있다고 하고 처리되면 없다고 한다`() {
        val userId = syntheticUserId()
        assertFalse(depositQuery.existsPendingByUserId(userId))

        val saved = depositRequests.save(DepositRequest.request(UserId(userId), 10_000, 1_000, "k", now))
        assertTrue(depositQuery.existsPendingByUserId(userId))

        val reloaded = requireNotNull(tx.execute { depositRequests.findById(saved.id!!) })
        reloaded.reject(UserId(7), "사유", now)
        depositRequests.save(reloaded)
        assertFalse(depositQuery.existsPendingByUserId(userId))
    }

    @Test
    fun `대기 중인 환전 요청은 있다고 하고 처리되면 없다고 한다`() {
        val userId = syntheticUserId()
        assertFalse(withdrawalQuery.existsPendingByUserId(userId))

        val bank = BankAccount.of("국민은행", "123456789012", "홍길동")
        val saved = withdrawalRequests.save(WithdrawalRequest.request(UserId(userId), 10_000, 1_000, bank, "k", now))
        assertTrue(withdrawalQuery.existsPendingByUserId(userId))

        val reloaded = requireNotNull(tx.execute { withdrawalRequests.findById(saved.id!!) })
        reloaded.reject(UserId(7), "사유", now)
        withdrawalRequests.save(reloaded)
        assertFalse(withdrawalQuery.existsPendingByUserId(userId))
    }

    @Test
    fun `다른 사용자의 대기 요청은 세지 않는다`() {
        depositRequests.save(DepositRequest.request(UserId(syntheticUserId()), 10_000, 1_000, "k", now))

        assertFalse(depositQuery.existsPendingByUserId(syntheticUserId() + 1))
    }

    /** 실제 IDENTITY id 와 겹치지 않는 대역(1조~)의 합성 userId. */
    private fun syntheticUserId(): Long = 1_000_000_000_000L + (System.nanoTime() ushr 1)
}
