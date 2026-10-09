package com.jsm.boardgame.holdem.presentation.ws.payload

/**
 * 공개 토픽 `/topic/tables/{id}` 로 한 번 나가는 닫힘 알림. [TablePublicView] 와 다른 타입이라 `type` 으로 구분한다 —
 * 클라이언트는 `type == "TABLE_CLOSED"` 이면 구독을 끊고 로비로 돌아간다. 카드 필드가 없다.
 */
data class TableClosedView(val tableId: Long, val type: String = "TABLE_CLOSED")
