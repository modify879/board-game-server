package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.wallet.infrastructure.persistence.entity.LedgerEntryJpaEntity
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.LedgerEntryJpaRepository
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.WalletJpaEntity
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.WalletJpaRepository
import com.jsm.boardgame.wallet.application.query.view.LedgerEntryView
import com.jsm.boardgame.wallet.application.query.view.WalletBalanceView
import com.jsm.boardgame.wallet.application.query.port.WalletQueryRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class WalletQueryRepositoryAdapter(
    private val walletJpa: WalletJpaRepository,
    private val ledgerJpa: LedgerEntryJpaRepository,
) : WalletQueryRepository {

    override fun findBalanceByUserId(userId: Long): WalletBalanceView? =
        walletJpa.findAll {
            selectNew<WalletBalanceView>(path(WalletJpaEntity::balance))
                .from(entity(WalletJpaEntity::class))
                .where(path(WalletJpaEntity::userId).eq(userId))
        }.firstOrNull()

    // 원장 조회가 지갑과 조인하지 않도록 id 만 따로 얻는다. 엔티티를 통째로 읽지 않는다.
    override fun findWalletIdByUserId(userId: Long): Long? =
        walletJpa.findAll {
            select<Long>(path(WalletJpaEntity::id))
                .from(entity(WalletJpaEntity::class))
                .where(path(WalletJpaEntity::userId).eq(userId))
        }.firstOrNull()

    override fun findLedgerByWalletId(walletId: Long, pageable: Pageable): Page<LedgerEntryView> =
        ledgerJpa.findPage(pageable) {
            selectNew<LedgerEntryView>(
                path(LedgerEntryJpaEntity::id),
                path(LedgerEntryJpaEntity::type),
                path(LedgerEntryJpaEntity::amount),
                path(LedgerEntryJpaEntity::balanceAfter),
                path(LedgerEntryJpaEntity::referenceType),
                path(LedgerEntryJpaEntity::referenceId),
                path(LedgerEntryJpaEntity::memo),
                path(LedgerEntryJpaEntity::occurredAt),
            ).from(entity(LedgerEntryJpaEntity::class))
                .where(path(LedgerEntryJpaEntity::walletId).eq(walletId))
                .orderBy(path(LedgerEntryJpaEntity::id).desc())
        }
}
