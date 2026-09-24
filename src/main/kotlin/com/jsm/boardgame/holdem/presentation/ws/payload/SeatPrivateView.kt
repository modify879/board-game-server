package com.jsm.boardgame.holdem.presentation.ws.payload

/** 개인 전용. 자기 홀카드와 지금 할 수 있는 행동만 담는다. */
data class SeatPrivateView(
    val tableId: Long,
    val seatNo: Int,
    val holeCards: List<String>,
    val availableActions: AvailableActionsView?,
)

data class AvailableActionsView(
    val canCheck: Boolean,
    val callAmount: Long?,
    val minRaiseTo: Long?,
    val maxRaiseTo: Long?,
)
