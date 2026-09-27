package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `WithdrawalRequestRepositoryAdapter` 가 저장한 필드를 그대로 왕복시키는지 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawalRequestRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var withdrawalRequests: WithdrawalRequestRepository

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    @Test
    fun `저장 후 findById 가 상태 금액 계좌 세 필드를 그대로 돌려준다`() {
        val request = WithdrawalRequest.request(userId = System.nanoTime(), amount = 10_000, bankAccount = bankAccount, at = now)
        val saved = withdrawalRequests.save(request)

        val reloaded = requireNotNull(withdrawalRequests.findById(saved.id!!))
        reloaded.reject(adminUserId = 7, reason = "증빙 불충분", at = now)
        withdrawalRequests.save(reloaded)

        val final = requireNotNull(withdrawalRequests.findById(saved.id))
        assertEquals(WithdrawalRequestStatus.REJECTED, final.status)
        assertEquals(10_000L, final.amount.amount)
        assertEquals("국민은행", final.bankAccount.bankName)
        assertEquals("123456789012", final.bankAccount.accountNumber)
        assertEquals("홍길동", final.bankAccount.accountHolder)
    }
}
