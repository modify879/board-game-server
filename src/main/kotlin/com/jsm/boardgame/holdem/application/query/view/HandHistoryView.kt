package com.jsm.boardgame.holdem.application.query.view

import java.time.Instant

/** [Player.holeCards] 는 마스킹 결과다 — 볼 수 없는 패는 null. */
data class HandHistoryView(
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
}
