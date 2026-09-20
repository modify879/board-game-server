package com.jsm.boardgame.wallet.application.query

import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class DepositRequestQueryService(
    private val depositRequestQuery: DepositRequestQueryRepository,
) {

    fun findMine(userId: Long, pageable: Pageable): Page<DepositRequestView> =
        depositRequestQuery.findByUserId(userId, pageable)

    fun findByStatus(status: DepositRequestStatus?, pageable: Pageable): Page<DepositRequestView> =
        depositRequestQuery.findByStatus(status, pageable)
}
