package com.jsm.boardgame.holdem.presentation.ws.payload

/** 개인 전용. 자기 홀카드만 담는다. */
data class SeatPrivateView(
    val tableId: Long,
    val seatNo: Int,
    val holeCards: List<String>,
)
