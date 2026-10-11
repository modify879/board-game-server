package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.holdem.application.port.HandHistory
import com.jsm.boardgame.holdem.application.port.HandHistoryEntry
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryPlayerJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryPlayerJpaRepository
import org.springframework.stereotype.Repository
import java.time.Clock

/** 정산·보여 주기 트랜잭션에 합류해 쓴다. 종료 시각은 공유 `Clock` 으로 찍는다. */
@Repository
class HandHistoryAdapter(
    private val hands: HandHistoryJpaRepository,
    private val players: HandHistoryPlayerJpaRepository,
    private val clock: Clock,
) : HandHistory {

    override fun record(entry: HandHistoryEntry) {
        val hand = hands.save(
            HandHistoryJpaEntity(
                tableId = entry.tableId,
                endedAt = clock.instant(),
                buttonSeatNo = entry.buttonSeatNo,
                board = entry.board.joinToString(" "),
            ),
        )
        players.saveAll(
            entry.players.map {
                HandHistoryPlayerJpaEntity(
                    handId = hand.id,
                    userId = it.userId,
                    seatNo = it.seatNo,
                    startingStack = it.startingStack,
                    endingStack = it.endingStack,
                    payout = it.payout,
                    holeCards = it.holeCards.joinToString(" "),
                    shown = it.shown,
                )
            },
        )
    }

    override fun markShown(tableId: TableId, userId: UserId) {
        players.markShownInLatestHand(tableId.value, userId.value)
    }
}
