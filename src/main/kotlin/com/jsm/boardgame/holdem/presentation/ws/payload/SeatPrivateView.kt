package com.jsm.boardgame.holdem.presentation.ws.payload

data class SeatPrivateView(
    val tableId: Long,
    val seatNo: Int,
    val holeCards: List<String>,
    /** 홀카드 + 보드로 본 내 현재 족보. 홀카드가 없으면 null. */
    val currentHand: CurrentHandView?,
    val availableActions: AvailableActionsView?,
    /** 내 퇴장 예약의 종류(`SPECTATE`·`LEAVE`). 예약이 없으면 null. */
    val leaveReservation: String?,
    /** 내 칩 추가 예약(요청 금액). 예약이 없으면 null. */
    val chipsReservation: Long?,
    /** 테이블별 단조 증가 순번. 클라이언트 계약은 [TablePublicView.seq] 참고. */
    val seq: Long,
)

data class CurrentHandView(val category: String, val tiebreakers: List<Int>)

data class AvailableActionsView(
    val canCheck: Boolean,
    val callAmount: Long?,
    val minRaiseTo: Long?,
    val maxRaiseTo: Long?,
)
