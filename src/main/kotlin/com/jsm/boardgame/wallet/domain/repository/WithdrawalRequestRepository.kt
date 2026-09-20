package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId

interface WithdrawalRequestRepository {
    fun findById(id: WithdrawalRequestId): WithdrawalRequest?
    fun save(request: WithdrawalRequest): WithdrawalRequest
}
