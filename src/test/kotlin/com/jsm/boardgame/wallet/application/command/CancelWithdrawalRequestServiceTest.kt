package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestNotFoundException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
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

private class CancelFakeWithdrawalRequestRepository : WithdrawalRequestRepository {
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

private class CancelFakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()

    override fun findByUserId(userId: Long): Wallet? = stored[userId]

    override fun save(wallet: Wallet): Wallet {
        stored[wallet.userId] = wallet
        return wallet
    }

    override fun findOrOpen(userId: Long): Wallet = findByUserId(userId) ?: save(Wallet.open(userId))
}

private class CancelFakeLedgerEntryRepository : LedgerEntryRepository {
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

class CancelWithdrawalRequestServiceTest {

    private val withdrawalRequests = CancelFakeWithdrawalRequestRepository()
    private val wallets = CancelFakeWalletRepository()
    private val ledger = CancelFakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = CancelWithdrawalRequestService(withdrawalRequests, wallets, ledger, clock)
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    private fun heldRequest(id: Long = 1, userId: Long = 1, amount: Long = 10_000): WithdrawalRequest {
        wallets.stored[userId] = Wallet.reconstitute(id = WalletId(userId), userId = userId, balance = Money.ZERO, version = 0)
        return WithdrawalRequest.reconstitute(
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
    }

    @Test
    fun `본인 취소 시 환급된다`() {
        val request = heldRequest(userId = 1, amount = 10_000)

        service.cancel(CancelWithdrawalRequestCommand(requestId = request.id!!.value, requesterUserId = 1))

        assertEquals(Money.of(10_000), wallets.findByUserId(1)!!.balance)
    }

    @Test
    // 남의 요청은 "없음" 으로 응답한다 — id 열거를 막기 위해서다(CancelDepositRequestServiceTest 참조).
    fun `남의 요청이면 WITHDRAWAL_REQUEST_NOT_FOUND 이고 잔액이 변하지 않는다`() {
        val request = heldRequest(userId = 1, amount = 10_000)

        val e = assertFailsWith<WithdrawalRequestNotFoundException> {
            service.cancel(CancelWithdrawalRequestCommand(requestId = request.id!!.value, requesterUserId = 2))
        }

        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_NOT_FOUND, e.errorCode)
        assertEquals(Money.ZERO, wallets.findByUserId(1)!!.balance)
    }
}
