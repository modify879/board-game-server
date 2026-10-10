package com.jsm.boardgame.holdem.presentation.ws.payload

/**
 * 브로드캐스트 전용. 홀카드를 담을 필드가 아예 없다 — 이게 이 타입의 존재 이유다.
 * 여기에 카드 필드를 추가하지 마라. 추가하는 순간 규칙 6의 컴파일러 보장이 사라진다.
 * 예외는 정확히 둘이다. 하나는 [revealedHands] — 올인으로 더 베팅할 수 없어 런아웃 중일 때만(TDA),
 * [com.jsm.boardgame.holdem.domain.model.Hand.revealedSeatNos] 로만 채워진다. 다른 하나는
 * [result] 의 [HandResultPublicView.shownHands] 다 — 핸드가 끝났고 쇼다운이 벌어졌을 때만,
 * [com.jsm.boardgame.holdem.domain.model.HandResult.shownSeatNos] 로만 채워진다(재구독 스냅샷에서는 저장된
 * 직전 결과에서 오는데, 그것도 같은 `shownSeatNos` 로만 만든 것이다) — 쇼다운까지 간 좌석은
 * 전원 공개한다(사용자 결정, 머크 없음). 나열 순서만 TDA 17(마지막 라운드의 마지막 공격자부터, 없으면
 * 버튼 다음 좌석부터)을 따른다. 그 둘 외에 어떤 카드 성격의 필드도 이 파일에 추가하지 마라 — 추가하는
 * 순간 관전이 정보 유출이 된다.
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
    /** 런아웃 중 공개된 패. 런아웃이 아니면 빈 리스트. */
    val revealedHands: List<RevealedHandView>,
    /** 다음 핸드까지 남은 ms. 클라이언트는 받은 순간부터 자기 단조 시계로 센다 — 벽시계와
     *  비교하지 않는다. 카운트다운이 없으면(핸드 진행 중이거나 인원이 모자라면) null. */
    val nextHandInMs: Long?,
    /** 현재 차례 좌석의 턴 타이머가 만료되기까지 남은 ms. 클라이언트는 받은 순간부터 자기 단조 시계로
     *  센다 — 벽시계와 비교하지 않는다. 턴 타이머가 없으면(핸드 없음·차례 없음·복구 유예 중) null. */
    val turnRemainingMs: Long?,
    /**
     * 테이블별 단조 증가 순번(TableViewSequence). 클라이언트 계약: 채널(공개/개인)별로 마지막
     * seq 를 기억하고 `seq < last` 인 메시지는 버린다(같으면 받는다. 같은 seq 는 항상 같은 상태다) — clientOutboundChannel 의
     * 스레드풀과 커밋 순서 역전 때문에 옛 스냅샷이 새 스냅샷보다 늦게 도착할 수 있다. 구독할 때
     * (재접속 포함) last 를 리셋한다.
     */
    val seq: Long,
)

data class SeatPublicView(
    val seatNo: Int,
    val userId: Long,
    val stack: Long,
    val totalContributed: Long,
    val status: String,
    val presence: String,
)

/**
 * 핸드가 끝난 시점의 결과. 정산 직후 0칩 좌석이 기립하고 대기자가 같은 좌석에 앉을 수 있어 좌석 번호만으로는
 * 주인을 알 수 없다 — 클라이언트는 [PayoutPublicView.userId]·[ShownHandView.userId] 를 그 좌석의 현재 점유자와
 * 맞춰 보고, 일치하지 않는 항목은 무시한다.
 */
data class HandResultPublicView(val payouts: List<PayoutPublicView>, val shownHands: List<ShownHandView>)

data class PayoutPublicView(val seatNo: Int, val userId: Long, val amount: Long)

/**
 * 쇼다운까지 간 좌석 전원의 패 — 이겼는지 여부와 무관하다(사용자 결정, 머크 없음). 나열 순서는
 * TDA 17(마지막 라운드의 마지막 공격자부터, 없으면 버튼 다음 좌석부터)을 따른다. 공개 뷰에서 카드를
 * 담을 수 있는 유일한 타입이다 — [com.jsm.boardgame.holdem.domain.model.HandResult.shownSeatNos] 로만
 * 만든다. 폴드한 좌석·폴드 승리·진행 중 핸드의 카드는 여기 오지 않는다.
 */
data class ShownHandView(val seatNo: Int, val userId: Long, val holeCards: List<String>, val category: String)

/** 런아웃 중 공개된 좌석의 패 — [com.jsm.boardgame.holdem.domain.model.Hand.revealedSeatNos] 로만 만든다. */
data class RevealedHandView(val seatNo: Int, val holeCards: List<String>)
