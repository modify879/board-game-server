package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.DepositRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.exception.DepositRequestNotFoundException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ApproveFakeDepositRequestRepository : DepositRequestRepository {
    val stored = mutableMapOf<Long, DepositRequest>()

    fun put(request: DepositRequest) {
        stored[request.id!!.value] = request
    }

    override fun findById(id: DepositRequestId): DepositRequest? = stored[id.value]

    override fun save(request: DepositRequest): DepositRequest {
        stored[request.id!!.value] = request
        return request
    }
}

private class ApproveFakeWalletRepository : WalletRepository {
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

private class ApproveFakeLedgerEntryRepository : LedgerEntryRepository {
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

class ApproveDepositRequestServiceTest {

    private val depositRequests = ApproveFakeDepositRequestRepository()
    private val wallets = ApproveFakeWalletRepository()
    private val ledger = ApproveFakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = ApproveDepositRequestService(depositRequests, wallets, ledger, clock)

    private fun pendingRequest(id: Long = 1, userId: Long = 1, amount: Long = 10_000): DepositRequest =
        DepositRequest.reconstitute(
            id = DepositRequestId(id),
            userId = userId,
            requestedAmount = Money.of(amount),
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = DepositRequestStatus.PENDING,
            creditedAmount = null,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            version = 0,
        ).also { depositRequests.put(it) }

    @Test
    fun `승인하면 지갑 잔액이 늘고 DEPOSIT 원장 엔트리가 하나 생긴다`() {
        val request = pendingRequest()

        service.approve(ApproveDepositRequestCommand(requestId = request.id!!.value, adminUserId = 99, creditedAmount = null))

        val wallet = wallets.findByUserId(1)!!
        assertEquals(Money.of(10_000), wallet.balance)
        assertEquals(1, ledger.stored.size)
        assertEquals(LedgerEntryType.DEPOSIT, ledger.stored.single().type)
    }

    @Test
    fun `원장 엔트리의 reference 가 DEPOSIT_REQUEST 요청 id 이고 balanceAfter 가 새 잔액이다`() {
        pendingRequest(id = 5, amount = 10_000)

        service.approve(ApproveDepositRequestCommand(requestId = 5, adminUserId = 99, creditedAmount = null))

        val entry = ledger.stored.single()
        assertEquals(LedgerReferenceType.DEPOSIT_REQUEST, entry.reference.type)
        assertEquals(5L, entry.reference.id)
        assertEquals(Money.of(10_000), entry.balanceAfter)
    }

    @Test
    fun `지갑이 없던 사용자는 승인 시 지갑이 만들어진다`() {
        val request = pendingRequest(userId = 42)

        service.approve(ApproveDepositRequestCommand(requestId = request.id!!.value, adminUserId = 99, creditedAmount = null))

        assertTrue(wallets.findByUserId(42) != null)
    }

    @Test
    fun `creditedAmount 를 주면 그 금액이 반영된다`() {
        val request = pendingRequest(amount = 10_000)

        service.approve(ApproveDepositRequestCommand(requestId = request.id!!.value, adminUserId = 99, creditedAmount = 9_000))

        assertEquals(Money.of(9_000), wallets.findByUserId(1)!!.balance)
        assertEquals(Money.of(9_000), depositRequests.findById(request.id)!!.creditedAmount)
    }

    @Test
    fun `creditedAmount 를 안 주면 요청 금액이 반영된다`() {
        val request = pendingRequest(amount = 10_000)

        service.approve(ApproveDepositRequestCommand(requestId = request.id!!.value, adminUserId = 99, creditedAmount = null))

        assertEquals(Money.of(10_000), wallets.findByUserId(1)!!.balance)
    }

    @Test
    fun `이미 처리된 요청을 승인하면 DEPOSIT_REQUEST_ALREADY_PROCESSED 이고 지갑 잔액이 변하지 않는다`() {
        val request = pendingRequest()
        request.approve(1, 10_000, Instant.now())
        depositRequests.save(request)

        val e = assertFailsWith<DepositRequestAlreadyProcessedException> {
            service.approve(ApproveDepositRequestCommand(requestId = request.id!!.value, adminUserId = 2, creditedAmount = null))
        }

        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, e.errorCode)
        // 두 번째 승인 시도가 request.approve() 에서 막혀 지갑 코드에 도달하지 않았으므로
        // 지갑 자체가 만들어지지 않았다.
        assertNull(wallets.findByUserId(1))
    }

    @Test
    fun `없는 요청이면 DEPOSIT_REQUEST_NOT_FOUND`() {
        val e = assertFailsWith<DepositRequestNotFoundException> {
            service.approve(ApproveDepositRequestCommand(requestId = 999, adminUserId = 1, creditedAmount = null))
        }
        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_NOT_FOUND, e.errorCode)
    }
}
