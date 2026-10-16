package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId

/** 서비스 테스트가 공유하는 인메모리 대역. id 가 없는 요청은 저장할 때 번호를 매긴다. [put] 은 미리 넣어 둘 때 쓴다. */
class FakeWithdrawalRequestRepository : WithdrawalRequestRepository {
    val stored = mutableMapOf<Long, WithdrawalRequest>()
    private var sequence = 0L

    fun put(request: WithdrawalRequest) {
        stored[request.id!!.value] = request
    }

    override fun findById(id: WithdrawalRequestId): WithdrawalRequest? = stored[id.value]

    override fun findByUserAndIdempotencyKey(userId: UserId, idempotencyKey: String): WithdrawalRequest? =
        stored.values.firstOrNull { it.userId == userId && it.idempotencyKey == idempotencyKey }

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
                idempotencyKey = request.idempotencyKey,
            )
        } else {
            request
        }
        stored[saved.id!!.value] = saved
        return saved
    }
}
