package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId

interface DepositRequestRepository {
    fun findById(id: DepositRequestId): DepositRequest?
    fun save(request: DepositRequest): DepositRequest
}
