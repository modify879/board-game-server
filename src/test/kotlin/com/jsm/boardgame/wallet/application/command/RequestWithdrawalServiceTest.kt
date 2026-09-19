package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class RequestFakeWithdrawalRequestRepository : WithdrawalRequestRepository {
    val stored = mutableMapOf<Long, WithdrawalRequest>()
    private var sequence = 0L

    override fun findById(id: WithdrawalRequestId): WithdrawalRequest? = stored[id.value]

    override fun save(request: WithdrawalRequest): WithdrawalRequest {
        val saved = if (request.id == null) {
            sequence += 1
            WithdrawalRequest.reconstitute(
                id = WithdrawalRequestId(sequence),
                userId = request.userId,
                amount = request.amount,
                bankAccount = request.bankAccount,
                requestedAt = request.requestedAt,
                status = request.status,
                processedBy = request.processedBy,
                processedAt = request.processedAt,
                rejectionReason = request.rejectionReason,
                version = request.version,
            )
        } else {
            request
        }
        stored[saved.id!!.value] = saved
        return saved
    }
}

private class RequestFakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()
    private var sequence = 0L

    override fun findByUserId(userId: Long): Wallet? = stored[userId]

    override fun save(wallet: Wallet): Wallet {
        val saved = if (wallet.id == null) {
            sequence += 1
            Wallet.reconstitute(id = WalletId(sequence), userId = wallet.userId, balance = wallet.balance, version = wallet.version)
        } else {
            wallet
        }
        stored[wallet.userId] = saved
        return saved
    }
}

private class RequestFakeLedgerEntryRepository : LedgerEntryRepository {
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

class RequestWithdrawalServiceTest {

    private val withdrawalRequests = RequestFakeWithdrawalRequestRepository()
    private val wallets = RequestFakeWalletRepository()
    private val ledger = RequestFakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = RequestWithdrawalService(withdrawalRequests, wallets, ledger, clock)

    private fun walletWithBalance(userId: Long, balance: Long) {
        wallets.stored[userId] = Wallet.reconstitute(id = WalletId(userId), userId = userId, balance = Money.of(balance), version = 0)
    }

    @Test
    fun `요청하면 잔액이 즉시 줄고 WITHDRAWAL_HOLD 엔트리가 생기며 reference 가 요청을 가리킨다`() {
        walletWithBalance(1, 10_000)

        val id = service.request(RequestWithdrawalCommand(1, 5_000, "국민은행", "123456789012", "홍길동"))

        assertEquals(Money.of(5_000), wallets.findByUserId(1)!!.balance)
        val entry = ledger.stored.single()
        assertEquals(LedgerEntryType.WITHDRAWAL_HOLD, entry.type)
        assertEquals(LedgerReferenceType.WITHDRAWAL_REQUEST, entry.reference.type)
        assertEquals(id.value, entry.reference.id)
    }

    @Test
    fun `잔액보다 큰 금액이면 INSUFFICIENT_BALANCE`() {
        walletWithBalance(1, 1_000)

        val e = assertFailsWith<InsufficientBalanceException> {
            service.request(RequestWithdrawalCommand(1, 5_000, "국민은행", "123456789012", "홍길동"))
        }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }

    @Test
    fun `지갑이 없는 사용자가 요청하면 INSUFFICIENT_BALANCE`() {
        val e = assertFailsWith<InsufficientBalanceException> {
            service.request(RequestWithdrawalCommand(1, 5_000, "국민은행", "123456789012", "홍길동"))
        }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }
}
