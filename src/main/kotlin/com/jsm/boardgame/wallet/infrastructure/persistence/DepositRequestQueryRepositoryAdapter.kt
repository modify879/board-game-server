package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.application.query.DepositRequestQueryRepository
import com.jsm.boardgame.wallet.application.query.DepositRequestView
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class DepositRequestQueryRepositoryAdapter(
    private val jpa: DepositRequestJpaRepository,
) : DepositRequestQueryRepository {

    override fun findByUserId(userId: Long, pageable: Pageable): Page<DepositRequestView> =
        jpa.findPage(pageable) {
            selectNew<DepositRequestView>(
                path(DepositRequestJpaEntity::id),
                path(DepositRequestJpaEntity::userId),
                path(DepositRequestJpaEntity::requestedAmount),
                path(DepositRequestJpaEntity::status),
                path(DepositRequestJpaEntity::creditedAmount),
                path(DepositRequestJpaEntity::requestedAt),
                path(DepositRequestJpaEntity::processedAt),
                path(DepositRequestJpaEntity::rejectionReason),
            ).from(entity(DepositRequestJpaEntity::class))
                .where(path(DepositRequestJpaEntity::userId).eq(userId))
                .orderBy(path(DepositRequestJpaEntity::id).desc())
        }

    // status 가 null 이면 whereAnd 에 null 조건만 남아 전체를 돌려준다 — 선택적 조건을 JDSL 로 표현하는 지점.
    override fun findByStatus(status: DepositRequestStatus?, pageable: Pageable): Page<DepositRequestView> =
        jpa.findPage(pageable) {
            selectNew<DepositRequestView>(
                path(DepositRequestJpaEntity::id),
                path(DepositRequestJpaEntity::userId),
                path(DepositRequestJpaEntity::requestedAmount),
                path(DepositRequestJpaEntity::status),
                path(DepositRequestJpaEntity::creditedAmount),
                path(DepositRequestJpaEntity::requestedAt),
                path(DepositRequestJpaEntity::processedAt),
                path(DepositRequestJpaEntity::rejectionReason),
            ).from(entity(DepositRequestJpaEntity::class))
                .whereAnd(status?.let { path(DepositRequestJpaEntity::status).eq(it.name) })
                .orderBy(path(DepositRequestJpaEntity::id).desc())
        }
}
