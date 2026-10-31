package com.jsm.boardgame.wallet.application.query.port

import com.jsm.boardgame.wallet.application.query.view.WithdrawalRequestView
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface WithdrawalRequestQueryRepository {
    fun findByUserId(userId: Long, pageable: Pageable): Page<WithdrawalRequestView>
    fun findByStatus(status: WithdrawalRequestStatus?, pageable: Pageable): Page<WithdrawalRequestView>
}
