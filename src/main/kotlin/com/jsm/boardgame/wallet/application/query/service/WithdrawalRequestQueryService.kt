package com.jsm.boardgame.wallet.application.query.service

import com.jsm.boardgame.wallet.application.query.port.WithdrawalRequestQueryRepository
import com.jsm.boardgame.wallet.application.query.view.WithdrawalRequestView
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

    /** 처리 대기 중인(PENDING) 환전 요청이 있는가. 다른 컨텍스트(탈퇴 사전 조건)가 쓰는 공개 계약이다. */
    fun hasPending(userId: Long): Boolean = withdrawalRequestQuery.existsPendingByUserId(userId)

    fun findByStatus(status: WithdrawalRequestStatus?, pageable: Pageable): Page<WithdrawalRequestView> =
        withdrawalRequestQuery.findByStatus(status, pageable)
}
