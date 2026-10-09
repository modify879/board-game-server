package com.jsm.boardgame.holdem.application.query.view

/**
 * stack 은 holdem_seats 에 저장된 값 그대로다. 핸드 진행 중이면 핸드 시작 시점 스택(그 핸드에 건 칩 포함)이고
 * 정산 때 갱신된다 — 핸드 중 실시간 스택은 공개 토픽의 TablePublicView 에 있다.
 */
data class MySeatView(val tableId: Long, val seatNo: Int, val handInProgress: Boolean, val stack: Long)
