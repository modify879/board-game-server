package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.CancelDepositRequestCommand
import com.jsm.boardgame.wallet.domain.exception.DepositRequestNotFoundException
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
    // 남의 요청은 "없음" 으로 응답한다 — 소유자 아님(403)과 존재하지 않음(404)이 갈리면
    // 아무 id 나 넣어보는 것만으로 남의 요청 존재 여부를 열거할 수 있다.
    fun `남의 요청이면 DEPOSIT_REQUEST_NOT_FOUND`() {
        pendingRequest(userId = 1)

        val e = assertFailsWith<DepositRequestNotFoundException> {
            service.cancel(CancelDepositRequestCommand(requestId = 1, requesterUserId = 2))
        }
        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_NOT_FOUND, e.errorCode)
    }
}
