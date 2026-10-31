package com.jsm.boardgame.holdem.presentation.ws.payload

/**
 * 개인 큐(`/user/queue/tables/{id}`)로 나가는 뷰. 브로드캐스트마다 그 테이블의 착석자 전원에게 간다 —
 * 핸드가 없어도, 이번 핸드에 참가하지 않아도 보낸다(칩·퇴장 예약은 핸드와 무관한 좌석 정보다).
 * 핸드에 묶인 정보는 [hand] 아래에만 있고, 핸드가 없거나 이 좌석이 이번 핸드에 참가하지 않았으면 null 이다.
 * 이 큐에는 주인 본인의 데이터만 흐르므로 [PrivateHandView.holeCards] 가 있어도 공개 뷰의 "마스킹으로 비우는 nullable 카드" 와 다르다.
 */
data class SeatPrivateView(
    val tableId: Long,
    val seatNo: Int,
    /** 내 퇴장 예약의 종류(`SPECTATE`·`LEAVE`). 예약이 없으면 null. */
    val leaveReservation: String?,
    /** 내 칩 추가 예약(요청 금액). 예약이 없으면 null. */
    val chipsReservation: Long?,
    /** 핸드 진행 중(방금 끝난 핸드 포함)이고 이 좌석이 참가했을 때만. 아니면 null. */
    val hand: PrivateHandView?,
    /** 테이블별 단조 증가 순번. 클라이언트 계약은 [TablePublicView.seq] 참고. */
    val seq: Long,
)

/** 이 좌석 본인의 핸드 정보. 다른 좌석의 카드는 담지 않는다. */
data class PrivateHandView(
    val holeCards: List<String>,
    /** 홀카드 + 보드로 본 내 현재 족보. 홀카드가 없으면 null. */
    val currentHand: CurrentHandView?,
    val availableActions: AvailableActionsView?,
)

data class CurrentHandView(val category: String, val tiebreakers: List<Int>)

data class AvailableActionsView(
    val canCheck: Boolean,
    val callAmount: Long?,
    val minRaiseTo: Long?,
    val maxRaiseTo: Long?,
)
