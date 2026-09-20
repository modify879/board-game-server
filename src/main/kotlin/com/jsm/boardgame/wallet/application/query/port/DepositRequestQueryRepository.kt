package com.jsm.boardgame.wallet.application.query.port

import com.jsm.boardgame.wallet.application.query.view.DepositRequestView
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface DepositRequestQueryRepository {
    fun findByUserId(userId: Long, pageable: Pageable): Page<DepositRequestView>
    fun findByStatus(status: DepositRequestStatus?, pageable: Pageable): Page<DepositRequestView>
}
