package com.jsm.boardgame.holdem.presentation.ws.payload

import java.time.Instant

/**
 * 브로드캐스트 전용. 홀카드를 담을 필드가 아예 없다 — 이게 이 타입의 존재 이유다.
 * 여기에 카드 필드를 추가하지 마라. 추가하는 순간 규칙 6의 컴파일러 보장이 사라진다.
 * 유일한 예외는 [result] 의 [HandResultPublicView.shownHands] 다 — 핸드가 끝났고 쇼다운이 벌어졌을 때만,
 * [com.jsm.boardgame.holdem.domain.model.HandResult.shownSeatNos] 로만 채워진다(TDA 17 순서대로 공개
 * 판정한 결과 — 이긴 좌석만이 아니라, 먼저 보여줄 차례였거나 그때까지 공개된 최고 패를 이기거나
 * 비긴 좌석도 포함한다). 그 외 어떤 카드 성격의 필드도 이 파일에 추가하지 마라 — 추가하는 순간
 * 관전이 정보 유출이 된다.
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
    val pendingSeatNos: List<Int>,
    /** 다음 핸드 자동 시작 예정 시각. 카운트다운용이며 핸드 진행 중이거나 인원이 모자라면 null. */
    val nextHandAt: Instant?,
)

data class SeatPublicView(
    val seatNo: Int,
    val userId: Long,
    val stack: Long,
    val totalContributed: Long,
    val status: String,
    val presence: String,
)

data class HandResultPublicView(val payouts: List<PayoutPublicView>, val shownHands: List<ShownHandView>)

data class PayoutPublicView(val seatNo: Int, val amount: Long)

/**
 * 쇼다운에서 패를 공개하는 좌석의 패 — 이겼다고 다는 아니다. TDA 17 순서상 먼저 보여줄 차례였거나
 * 그때까지의 최고 패를 이기거나 비긴 좌석도 포함한다. 공개 뷰에서 카드를 담을 수 있는 유일한 타입이다 —
 * [com.jsm.boardgame.holdem.domain.model.HandResult.shownSeatNos] 로만 만든다. 머크한 좌석·폴드
 * 승리·진행 중 핸드의 카드는 여기 오지 않는다.
 */
data class ShownHandView(val seatNo: Int, val holeCards: List<String>, val category: String)
