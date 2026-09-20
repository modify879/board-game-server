package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RejectDepositRequestCommand
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

private class RejectFakeDepositRequestRepository : DepositRequestRepository {
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

// RejectDepositRequestService 는 지갑·원장 출력 포트를 아예 의존하지 않는다.
// 그래서 "잔액도 원장도 변하지 않는다"는 페이크를 만들 필요 없이 타입으로 이미 보장된다.
class RejectDepositRequestServiceTest {

    private val depositRequests = RejectFakeDepositRequestRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = RejectDepositRequestService(depositRequests, clock)

    @Test
    fun `반려하면 상태와 사유만 바뀐다`() {
        val request = DepositRequest.reconstitute(
            id = DepositRequestId(1),
            userId = 1,
            requestedAmount = Money.of(10_000),
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = DepositRequestStatus.PENDING,
            creditedAmount = null,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            version = 0,
        )
        depositRequests.put(request)

        service.reject(RejectDepositRequestCommand(requestId = 1, adminUserId = 99, reason = "증빙 불충분"))

        val reloaded = depositRequests.findById(DepositRequestId(1))!!
        assertEquals(DepositRequestStatus.REJECTED, reloaded.status)
        assertEquals("증빙 불충분", reloaded.rejectionReason)
        assertEquals(99L, reloaded.processedBy)
    }
}
