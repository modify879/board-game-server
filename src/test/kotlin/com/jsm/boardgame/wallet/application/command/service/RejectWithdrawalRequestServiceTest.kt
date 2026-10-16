package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RejectWithdrawalRequestCommand
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.application.event.WalletCredited
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.jsm.boardgame.wallet.domain.repository.FakeLedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWalletRepository
import com.jsm.boardgame.wallet.application.event.FakeEventPublisher
import com.jsm.boardgame.wallet.domain.repository.FakeWithdrawalRequestRepository

class RejectWithdrawalRequestServiceTest {

    private val withdrawalRequests = FakeWithdrawalRequestRepository()
    private val wallets = FakeWalletRepository()
    private val ledger = FakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val eventPublisher = FakeEventPublisher()
    private val service = RejectWithdrawalRequestService(withdrawalRequests, wallets, ledger, clock, eventPublisher)
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    // 요청 시점에 이미 차감되어 잔액이 0인 지갑을 흉내낸다.
    private fun heldRequest(
        id: Long = 1,
        userId: Long = 1,
        amount: Long = 10_000,
        status: WithdrawalRequestStatus = WithdrawalRequestStatus.PENDING,
    ): WithdrawalRequest {
        wallets.stored[userId] = Wallet.reconstitute(id = WalletId(userId), userId = UserId(userId), balance = Money.ZERO)
        return WithdrawalRequest.reconstitute(
            id = WithdrawalRequestId(id),
            userId = UserId(userId),
            amount = Money.of(amount),
            bankAccount = bankAccount,
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = status,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            idempotencyKey = null,
        ).also { withdrawalRequests.put(it) }
    }

    @Test
    fun `반려하면 WalletCredited 를 발행한다`() {
        val request = heldRequest(userId = 7)

        service.reject(RejectWithdrawalRequestCommand(requestId = request.id!!.value, adminUserId = 99, reason = "서류 미비"))

        assertEquals(listOf<Any>(WalletCredited(7)), eventPublisher.events)
    }

    @Test
    fun `반려하면 잔액이 원래대로 복구되고 WITHDRAWAL_REFUND 엔트리가 생긴다`() {
        val request = heldRequest(amount = 10_000)

        service.reject(RejectWithdrawalRequestCommand(requestId = request.id!!.value, adminUserId = 99, reason = "서류 미비"))

        assertEquals(Money.of(10_000), wallets.findByUserId(UserId(1))!!.balance)
        assertEquals(LedgerEntryType.WITHDRAWAL_REFUND, ledger.stored.single().type)
    }

    @Test
    fun `이미 처리된 요청을 반려하면 ALREADY_PROCESSED 이고 잔액이 변하지 않는다`() {
        val request = heldRequest(amount = 10_000, status = WithdrawalRequestStatus.APPROVED)

        val e = assertFailsWith<WithdrawalRequestAlreadyProcessedException> {
            service.reject(RejectWithdrawalRequestCommand(requestId = request.id!!.value, adminUserId = 99, reason = "서류 미비"))
        }

        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, e.errorCode)
        assertEquals(Money.ZERO, wallets.findByUserId(UserId(1))!!.balance)
        assertTrue(ledger.stored.isEmpty())
    }
}
