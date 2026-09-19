package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestNotFoundException
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class ApproveFakeWithdrawalRequestRepository : WithdrawalRequestRepository {
    val stored = mutableMapOf<Long, WithdrawalRequest>()

    fun put(request: WithdrawalRequest) {
        stored[request.id!!.value] = request
    }

    override fun findById(id: WithdrawalRequestId): WithdrawalRequest? = stored[id.value]

    override fun save(request: WithdrawalRequest): WithdrawalRequest {
        stored[request.id!!.value] = request
        return request
    }
}

// ApproveWithdrawalRequestService 는 지갑·원장 출력 포트를 아예 의존하지 않는다(생성자에 없다).
// 그래서 "잔액도 원장도 변하지 않는다"는 페이크를 만들 필요 없이 타입으로 이미 보장된다.
class ApproveWithdrawalRequestServiceTest {

    private val withdrawalRequests = ApproveFakeWithdrawalRequestRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = ApproveWithdrawalRequestService(withdrawalRequests, clock)
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    private fun pendingRequest(id: Long = 1, userId: Long = 1, amount: Long = 10_000): WithdrawalRequest =
        WithdrawalRequest.reconstitute(
            id = WithdrawalRequestId(id),
            userId = userId,
            amount = Money.of(amount),
            bankAccount = bankAccount,
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = WithdrawalRequestStatus.PENDING,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            version = 0,
        ).also { withdrawalRequests.put(it) }

    @Test
    fun `승인하면 상태와 처리자만 바뀐다`() {
        val request = pendingRequest()

        service.approve(ApproveWithdrawalRequestCommand(requestId = request.id!!.value, adminUserId = 99))

        val reloaded = withdrawalRequests.findById(request.id)!!
        assertEquals(WithdrawalRequestStatus.APPROVED, reloaded.status)
        assertEquals(99L, reloaded.processedBy)
    }

    @Test
    fun `두 번 승인하면 WITHDRAWAL_REQUEST_ALREADY_PROCESSED`() {
        val request = pendingRequest()
        service.approve(ApproveWithdrawalRequestCommand(requestId = request.id!!.value, adminUserId = 99))

        val e = assertFailsWith<WithdrawalRequestAlreadyProcessedException> {
            service.approve(ApproveWithdrawalRequestCommand(requestId = request.id.value, adminUserId = 99))
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, e.errorCode)
    }

    @Test
    fun `없는 요청이면 WITHDRAWAL_REQUEST_NOT_FOUND`() {
        val e = assertFailsWith<WithdrawalRequestNotFoundException> {
            service.approve(ApproveWithdrawalRequestCommand(requestId = 999, adminUserId = 1))
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_NOT_FOUND, e.errorCode)
    }
}
