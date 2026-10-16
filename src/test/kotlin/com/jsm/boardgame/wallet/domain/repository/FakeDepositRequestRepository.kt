package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.UserId

/** 서비스 테스트가 공유하는 인메모리 대역. id 가 없는 요청은 저장할 때 번호를 매긴다. [put] 은 미리 넣어 둘 때 쓴다. */
class FakeDepositRequestRepository : DepositRequestRepository {
    val stored = mutableMapOf<Long, DepositRequest>()
    private var sequence = 0L

    fun put(request: DepositRequest) {
        stored[request.id!!.value] = request
    }

    override fun findById(id: DepositRequestId): DepositRequest? = stored[id.value]

    override fun findByUserAndIdempotencyKey(userId: UserId, idempotencyKey: String): DepositRequest? =
        stored.values.firstOrNull { it.userId == userId && it.idempotencyKey == idempotencyKey }

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
                idempotencyKey = request.idempotencyKey,
            )
        } else {
            request
        }
        stored[saved.id!!.value] = saved
        return saved
    }
}
