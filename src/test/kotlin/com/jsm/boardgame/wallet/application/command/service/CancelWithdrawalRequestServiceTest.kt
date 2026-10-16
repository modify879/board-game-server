package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.CancelWithdrawalRequestCommand
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestNotFoundException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.BankAccount
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
import com.jsm.boardgame.wallet.domain.repository.FakeLedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWalletRepository
import com.jsm.boardgame.wallet.application.event.FakeEventPublisher
import com.jsm.boardgame.wallet.domain.repository.FakeWithdrawalRequestRepository

class CancelWithdrawalRequestServiceTest {

    private val withdrawalRequests = FakeWithdrawalRequestRepository()
    private val wallets = FakeWalletRepository()
    private val ledger = FakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val eventPublisher = FakeEventPublisher()
    private val service = CancelWithdrawalRequestService(withdrawalRequests, wallets, ledger, clock, eventPublisher)
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    private fun heldRequest(id: Long = 1, userId: Long = 1, amount: Long = 10_000): WithdrawalRequest {
        wallets.stored[userId] = Wallet.reconstitute(id = WalletId(userId), userId = UserId(userId), balance = Money.ZERO)
        return WithdrawalRequest.reconstitute(
            id = WithdrawalRequestId(id),
            userId = UserId(userId),
            amount = Money.of(amount),
            bankAccount = bankAccount,
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = WithdrawalRequestStatus.PENDING,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            idempotencyKey = null,
        ).also { withdrawalRequests.put(it) }
    }

    @Test
    fun `취소하면 WalletCredited 를 발행한다`() {
        val request = heldRequest(userId = 7)

        service.cancel(CancelWithdrawalRequestCommand(requestId = request.id!!.value, requesterUserId = 7))

        assertEquals(listOf<Any>(WalletCredited(7)), eventPublisher.events)
    }

    @Test
    fun `본인 취소 시 환급된다`() {
        val request = heldRequest(userId = 1, amount = 10_000)

        service.cancel(CancelWithdrawalRequestCommand(requestId = request.id!!.value, requesterUserId = 1))

        assertEquals(Money.of(10_000), wallets.findByUserId(UserId(1))!!.balance)
    }

    @Test
    // 남의 요청은 "없음" 으로 응답한다 — id 열거를 막기 위해서다(CancelDepositRequestServiceTest 참조).
    fun `남의 요청이면 WITHDRAWAL_REQUEST_NOT_FOUND 이고 잔액이 변하지 않는다`() {
        val request = heldRequest(userId = 1, amount = 10_000)

        val e = assertFailsWith<WithdrawalRequestNotFoundException> {
            service.cancel(CancelWithdrawalRequestCommand(requestId = request.id!!.value, requesterUserId = 2))
        }

        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_NOT_FOUND, e.errorCode)
        assertEquals(Money.ZERO, wallets.findByUserId(UserId(1))!!.balance)
    }
}
