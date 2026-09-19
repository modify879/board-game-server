package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 이중 처리 방어의 두 번째 겹(DB 낙관적 락)을 검증한다.
 * DepositRequestRepositoryAdapterIntegrationTest 와 같은 이유다 —
 * saveAndFlush 회귀가 생기면 이 테스트가 실패한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawalRequestRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var withdrawalRequests: WithdrawalRequestRepository

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    @Test
    fun `같은 version 으로 두 번 저장하면 두 번째가 WithdrawalRequestAlreadyProcessedException`() {
        val saved = withdrawalRequests.save(
            WithdrawalRequest.request(userId = System.nanoTime(), amount = Money.of(10_000), bankAccount = bankAccount, at = now),
        )

        // 두 관리자가 동시에 같은 요청을 읽었다고 가정한다 — 둘 다 version 0 을 들고 있다.
        val firstView = requireNotNull(withdrawalRequests.findById(saved.id!!))
        val secondView = requireNotNull(withdrawalRequests.findById(saved.id))

        firstView.approve(1, now)
        withdrawalRequests.save(firstView)

        secondView.reject(2, "사유", now)

        // saveAndFlush 가 아니면 이 테스트가 실패한다: save 만 쓰면 UPDATE 의 @Version 충돌이
        // 트랜잭션 커밋 시점까지 미뤄져 어댑터의 catch 를 지나쳐 버린다.
        val e = assertFailsWith<WithdrawalRequestAlreadyProcessedException> {
            withdrawalRequests.save(secondView)
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, e.errorCode)
    }

    @Test
    fun `저장 후 findById 가 상태 금액 계좌 세 필드를 그대로 돌려준다`() {
        val request = WithdrawalRequest.request(userId = System.nanoTime(), amount = Money.of(10_000), bankAccount = bankAccount, at = now)
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
