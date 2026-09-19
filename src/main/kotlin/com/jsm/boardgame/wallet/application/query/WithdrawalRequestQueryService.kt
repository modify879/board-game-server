package com.jsm.boardgame.wallet.application.query

import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class WithdrawalRequestQueryService(
    private val withdrawalRequestQuery: WithdrawalRequestQueryRepository,
) {

    fun findMine(userId: Long, pageable: Pageable): Page<WithdrawalRequestView> =
        withdrawalRequestQuery.findByUserId(userId, pageable)

    fun findByStatus(status: WithdrawalRequestStatus?, pageable: Pageable): Page<WithdrawalRequestView> =
        withdrawalRequestQuery.findByStatus(status, pageable)
}
