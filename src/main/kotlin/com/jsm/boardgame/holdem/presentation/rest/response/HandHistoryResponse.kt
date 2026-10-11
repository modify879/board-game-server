package com.jsm.boardgame.holdem.presentation.rest.response

import com.jsm.boardgame.holdem.application.query.view.HandHistoryView
import java.time.Instant

data class HandHistoryResponse(
    val handId: Long,
    val tableId: Long,
    val endedAt: Instant,
    val buttonSeatNo: Int,
    val board: List<String>,
    val players: List<Player>,
) {
    data class Player(
        val seatNo: Int,
        val userId: Long,
        val startingStack: Long,
        val endingStack: Long,
        val payout: Long,
        val holeCards: List<String>?,
        val shown: Boolean,
    )

    companion object {
        fun from(view: HandHistoryView) = HandHistoryResponse(
            view.handId, view.tableId, view.endedAt, view.buttonSeatNo, view.board,
            view.players.map { Player(it.seatNo, it.userId, it.startingStack, it.endingStack, it.payout, it.holeCards, it.shown) },
        )
    }
}
