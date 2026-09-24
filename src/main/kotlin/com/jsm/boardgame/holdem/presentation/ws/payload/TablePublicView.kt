package com.jsm.boardgame.holdem.presentation.ws.payload

/**
 * 브로드캐스트 전용. 홀카드를 담을 필드가 아예 없다 — 이게 이 타입의 존재 이유다.
 * 여기에 카드 필드를 추가하지 마라. 추가하는 순간 규칙 6의 컴파일러 보장이 사라진다.
 * [result] 는 핸드가 끝났을 때만 채워지고, 받은 칩과 이긴 좌석의 족보 이름만 담는다 —
 * 진 좌석은 머크하는 룰이라 그 족보를 보내면 공개되지 않았을 정보가 샌다.
 */
data class TablePublicView(
    val tableId: Long,
    val handInProgress: Boolean,
    val street: String?,
    val board: List<String>,
    val pot: Long,
    val toActSeatNo: Int?,
    val buttonSeatNo: Int?,
    val seats: List<SeatPublicView>,
    val result: HandResultPublicView?,
)

data class SeatPublicView(
    val seatNo: Int,
    val userId: Long,
    val stack: Long,
    val totalContributed: Long,
    val status: String,
    val presence: String,
)

data class HandResultPublicView(val payouts: List<PayoutPublicView>)

/** [shownCategory] 는 쇼다운에서 이겨 패를 공개한 좌석만 채운다. 폴드로 이겼거나 진 좌석은 null. */
data class PayoutPublicView(val seatNo: Int, val amount: Long, val shownCategory: String?)
