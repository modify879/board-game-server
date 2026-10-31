package com.jsm.boardgame.wallet.application.query.service

import com.jsm.boardgame.wallet.application.query.port.DepositRequestQueryRepository
import com.jsm.boardgame.wallet.application.query.view.DepositRequestView
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

    /** 처리 대기 중인(PENDING) 충전 요청이 있는가. 다른 컨텍스트(탈퇴 사전 조건)가 쓰는 공개 계약이다. */
    fun hasPending(userId: Long): Boolean = depositRequestQuery.existsPendingByUserId(userId)

    fun findByStatus(status: DepositRequestStatus?, pageable: Pageable): Page<DepositRequestView> =
        depositRequestQuery.findByStatus(status, pageable)
}
