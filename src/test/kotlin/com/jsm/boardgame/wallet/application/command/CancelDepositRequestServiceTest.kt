package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.NotRequestOwnerException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class CancelFakeDepositRequestRepository : DepositRequestRepository {
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

class CancelDepositRequestServiceTest {

    private val depositRequests = CancelFakeDepositRequestRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = CancelDepositRequestService(depositRequests, clock)

    private fun pendingRequest(userId: Long = 1): DepositRequest =
        DepositRequest.reconstitute(
            id = DepositRequestId(1),
            userId = userId,
            requestedAmount = Money.of(10_000),
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = DepositRequestStatus.PENDING,
            creditedAmount = null,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            version = 0,
        ).also { depositRequests.put(it) }

    @Test
    fun `본인의 PENDING 요청이 취소된다`() {
        pendingRequest(userId = 1)

        service.cancel(CancelDepositRequestCommand(requestId = 1, requesterUserId = 1))

        assertEquals(DepositRequestStatus.CANCELED, depositRequests.findById(DepositRequestId(1))!!.status)
    }

    @Test
    fun `남의 요청이면 NOT_REQUEST_OWNER`() {
        pendingRequest(userId = 1)

        val e = assertFailsWith<NotRequestOwnerException> {
            service.cancel(CancelDepositRequestCommand(requestId = 1, requesterUserId = 2))
        }
        assertEquals(WalletErrorCode.NOT_REQUEST_OWNER, e.errorCode)
    }
}
