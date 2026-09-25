package com.jsm.boardgame.holdem.presentation.ws.payload

data class SeatPrivateView(
    val tableId: Long,
    val seatNo: Int,
    val holeCards: List<String>,
    val availableActions: AvailableActionsView?,
    /** 테이블별 단조 증가 순번. 클라이언트 계약은 [TablePublicView.seq] 참고. */
    val seq: Long,
)

data class AvailableActionsView(
    val canCheck: Boolean,
    val callAmount: Long?,
    val minRaiseTo: Long?,
    val maxRaiseTo: Long?,
)
