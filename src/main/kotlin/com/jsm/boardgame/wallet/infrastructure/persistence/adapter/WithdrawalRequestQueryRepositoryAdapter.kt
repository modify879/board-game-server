package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.wallet.infrastructure.persistence.entity.WithdrawalRequestJpaEntity
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.WithdrawalRequestJpaRepository
import com.jsm.boardgame.wallet.application.query.port.WithdrawalRequestQueryRepository
import com.jsm.boardgame.wallet.application.query.view.WithdrawalRequestView
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class WithdrawalRequestQueryRepositoryAdapter(
    private val jpa: WithdrawalRequestJpaRepository,
) : WithdrawalRequestQueryRepository {

    override fun findByUserId(userId: Long, pageable: Pageable): Page<WithdrawalRequestView> =
        jpa.findPage(pageable) {
            selectNew<WithdrawalRequestView>(
                path(WithdrawalRequestJpaEntity::id),
                path(WithdrawalRequestJpaEntity::userId),
                path(WithdrawalRequestJpaEntity::amount),
                path(WithdrawalRequestJpaEntity::status),
                path(WithdrawalRequestJpaEntity::bankName),
                path(WithdrawalRequestJpaEntity::accountNumber),
                path(WithdrawalRequestJpaEntity::accountHolder),
                path(WithdrawalRequestJpaEntity::requestedAt),
                path(WithdrawalRequestJpaEntity::processedAt),
                path(WithdrawalRequestJpaEntity::rejectionReason),
            ).from(entity(WithdrawalRequestJpaEntity::class))
                .where(path(WithdrawalRequestJpaEntity::userId).eq(userId))
                .orderBy(path(WithdrawalRequestJpaEntity::id).desc())
        }

    override fun findByStatus(status: WithdrawalRequestStatus?, pageable: Pageable): Page<WithdrawalRequestView> =
        jpa.findPage(pageable) {
            selectNew<WithdrawalRequestView>(
                path(WithdrawalRequestJpaEntity::id),
                path(WithdrawalRequestJpaEntity::userId),
                path(WithdrawalRequestJpaEntity::amount),
                path(WithdrawalRequestJpaEntity::status),
                path(WithdrawalRequestJpaEntity::bankName),
                path(WithdrawalRequestJpaEntity::accountNumber),
                path(WithdrawalRequestJpaEntity::accountHolder),
                path(WithdrawalRequestJpaEntity::requestedAt),
                path(WithdrawalRequestJpaEntity::processedAt),
                path(WithdrawalRequestJpaEntity::rejectionReason),
            ).from(entity(WithdrawalRequestJpaEntity::class))
                .whereAnd(status?.let { path(WithdrawalRequestJpaEntity::status).eq(it.name) })
                .orderBy(path(WithdrawalRequestJpaEntity::id).desc())
        }
}
