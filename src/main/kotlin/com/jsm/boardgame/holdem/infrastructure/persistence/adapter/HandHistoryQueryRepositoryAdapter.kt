package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.holdem.application.query.port.HandHistoryQueryRepository
import com.jsm.boardgame.holdem.application.query.port.HandHistoryQueryRepository.HandRow
import com.jsm.boardgame.holdem.application.query.port.HandHistoryQueryRepository.PlayerRow
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryPlayerJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryPlayerJpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class HandHistoryQueryRepositoryAdapter(
    private val hands: HandHistoryJpaRepository,
    private val players: HandHistoryPlayerJpaRepository,
) : HandHistoryQueryRepository {

    override fun findHands(playerUserId: Long?, tableId: Long?, pageable: Pageable): Page<HandRow> =
        hands.findPage(pageable) {
            val participated = playerUserId?.let {
                path(HandHistoryJpaEntity::id).`in`(
                    select(path(HandHistoryPlayerJpaEntity::handId))
                        .from(entity(HandHistoryPlayerJpaEntity::class))
                        .where(path(HandHistoryPlayerJpaEntity::userId).eq(it))
                        .asSubquery(),
                )
            }
            selectNew<HandRow>(
                path(HandHistoryJpaEntity::id),
                path(HandHistoryJpaEntity::tableId),
                path(HandHistoryJpaEntity::endedAt),
                path(HandHistoryJpaEntity::buttonSeatNo),
                path(HandHistoryJpaEntity::board),
            ).from(entity(HandHistoryJpaEntity::class))
                .whereAnd(participated, tableId?.let { path(HandHistoryJpaEntity::tableId).eq(it) })
                .orderBy(path(HandHistoryJpaEntity::endedAt).desc(), path(HandHistoryJpaEntity::id).desc())
        }

    override fun findPlayers(handIds: List<Long>): List<PlayerRow> {
        if (handIds.isEmpty()) return emptyList()
        return players.findAll {
            selectNew<PlayerRow>(
                path(HandHistoryPlayerJpaEntity::handId),
                path(HandHistoryPlayerJpaEntity::seatNo),
                path(HandHistoryPlayerJpaEntity::userId),
                path(HandHistoryPlayerJpaEntity::startingStack),
                path(HandHistoryPlayerJpaEntity::endingStack),
                path(HandHistoryPlayerJpaEntity::payout),
                path(HandHistoryPlayerJpaEntity::holeCards),
                path(HandHistoryPlayerJpaEntity::shown),
            ).from(entity(HandHistoryPlayerJpaEntity::class))
                .where(path(HandHistoryPlayerJpaEntity::handId).`in`(handIds))
        }.filterNotNull()
    }
}
