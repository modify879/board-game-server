package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RejectWithdrawalRequestCommand
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class RejectFakeWithdrawalRequestRepository : WithdrawalRequestRepository {
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

private class RejectFakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()

    override fun findByUserId(userId: Long): Wallet? = stored[userId]

    override fun save(wallet: Wallet): Wallet {
        stored[wallet.userId] = wallet
        return wallet
    }

    override fun findOrOpen(userId: Long): Wallet = findByUserId(userId) ?: save(Wallet.open(userId))
}

private class RejectFakeLedgerEntryRepository : LedgerEntryRepository {
    val stored = mutableListOf<LedgerEntry>()
    private var sequence = 0L

    override fun save(entry: LedgerEntry): LedgerEntry {
        sequence += 1
        val saved = LedgerEntry.reconstitute(
            id = LedgerEntryId(sequence),
            walletId = entry.walletId,
            type = entry.type,
            amount = entry.amount,
            balanceAfter = entry.balanceAfter,
            reference = entry.reference,
            memo = entry.memo,
            occurredAt = entry.occurredAt,
        )
        stored += saved
        return saved
    }
}

class RejectWithdrawalRequestServiceTest {

    private val withdrawalRequests = RejectFakeWithdrawalRequestRepository()
    private val wallets = RejectFakeWalletRepository()
    private val ledger = RejectFakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = RejectWithdrawalRequestService(withdrawalRequests, wallets, ledger, clock)
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    // 요청 시점에 이미 차감되어 잔액이 0인 지갑을 흉내낸다.
    private fun heldRequest(
        id: Long = 1,
        userId: Long = 1,
        amount: Long = 10_000,
        status: WithdrawalRequestStatus = WithdrawalRequestStatus.PENDING,
    ): WithdrawalRequest {
        wallets.stored[userId] = Wallet.reconstitute(id = WalletId(userId), userId = userId, balance = Money.ZERO, version = 0)
        return WithdrawalRequest.reconstitute(
            id = WithdrawalRequestId(id),
            userId = userId,
            amount = Money.of(amount),
            bankAccount = bankAccount,
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = status,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            version = 0,
        ).also { withdrawalRequests.put(it) }
    }

    @Test
    fun `반려하면 잔액이 원래대로 복구되고 WITHDRAWAL_REFUND 엔트리가 생긴다`() {
        val request = heldRequest(amount = 10_000)

        service.reject(RejectWithdrawalRequestCommand(requestId = request.id!!.value, adminUserId = 99, reason = "서류 미비"))

        assertEquals(Money.of(10_000), wallets.findByUserId(1)!!.balance)
        assertEquals(LedgerEntryType.WITHDRAWAL_REFUND, ledger.stored.single().type)
    }

    @Test
    fun `이미 처리된 요청을 반려하면 ALREADY_PROCESSED 이고 잔액이 변하지 않는다`() {
        val request = heldRequest(amount = 10_000, status = WithdrawalRequestStatus.APPROVED)

        val e = assertFailsWith<WithdrawalRequestAlreadyProcessedException> {
            service.reject(RejectWithdrawalRequestCommand(requestId = request.id!!.value, adminUserId = 99, reason = "서류 미비"))
        }

        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, e.errorCode)
        assertEquals(Money.ZERO, wallets.findByUserId(1)!!.balance)
        assertTrue(ledger.stored.isEmpty())
    }
}
