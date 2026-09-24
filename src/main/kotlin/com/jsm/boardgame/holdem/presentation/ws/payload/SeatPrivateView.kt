package com.jsm.boardgame.holdem.presentation.ws.payload

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
