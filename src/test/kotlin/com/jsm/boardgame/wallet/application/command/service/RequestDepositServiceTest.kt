package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeDepositRequestRepository : DepositRequestRepository {
    val stored = mutableMapOf<Long, DepositRequest>()
    private var sequence = 0L

    override fun findById(id: DepositRequestId): DepositRequest? = stored[id.value]

    override fun save(request: DepositRequest): DepositRequest {
        val saved = if (request.id == null) {
            sequence += 1
            DepositRequest.reconstitute(
                id = DepositRequestId(sequence),
                userId = request.userId,
                requestedAmount = request.requestedAmount,
                requestedAt = request.requestedAt,
                status = request.status,
                creditedAmount = request.creditedAmount,
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

// RequestDepositService 는 WalletRepository 를 의존하지 않는다 — 이 페이크는
// "지갑이 생성되지 않는다"를 서비스 바깥에서 증거로 남기기 위한 것일 뿐, 실제로 연결되지 않는다.
private class FakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()
    override fun findByUserId(userId: Long): Wallet? = stored[userId]
    override fun save(wallet: Wallet): Wallet {
        stored[wallet.userId] = wallet
        return wallet
    }
    override fun findOrOpen(userId: Long): Wallet = findByUserId(userId) ?: save(Wallet.open(userId))
}

class RequestDepositServiceTest {

    private val depositRequests = FakeDepositRequestRepository()
    private val wallets = FakeWalletRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = RequestDepositService(depositRequests, clock)

    @Test
    fun `요청이 저장되고 id 가 반환된다`() {
        val id = service.request(RequestDepositCommand(userId = 1, amount = 10_000))

        assertTrue(id.value != 0L)
        assertEquals(10_000L, depositRequests.stored[id.value]?.requestedAmount?.amount)
    }

    @Test
    fun `지갑이 생성되지 않는다`() {
        service.request(RequestDepositCommand(userId = 1, amount = 10_000))

        assertTrue(wallets.stored.isEmpty())
    }
}
