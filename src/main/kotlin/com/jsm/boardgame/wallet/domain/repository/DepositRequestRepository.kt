package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.UserId

interface DepositRequestRepository {
    fun findById(id: DepositRequestId): DepositRequest?
    fun findByUserAndIdempotencyKey(userId: UserId, idempotencyKey: String): DepositRequest?
    fun save(request: DepositRequest): DepositRequest
}
