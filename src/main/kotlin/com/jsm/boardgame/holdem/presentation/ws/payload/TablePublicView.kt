package com.jsm.boardgame.holdem.presentation.ws.payload

/**
 * 브로드캐스트 전용. 홀카드를 담을 필드가 아예 없다 — 이게 이 타입의 존재 이유다.
 * 여기에 카드 필드를 추가하지 마라. 추가하는 순간 규칙 6의 컴파일러 보장이 사라진다.
 * 예외는 정확히 둘이다. 하나는 [revealedHands] — 올인으로 더 베팅할 수 없어 런아웃 중일 때만(TDA),
 * [com.jsm.boardgame.holdem.domain.model.Hand.revealedSeatNos] 로만 채워진다. 다른 하나는
 * [result] 의 [HandResultPublicView.shownHands] 다 — 핸드가 끝났을 때만, 쇼다운까지 간 좌석은
 * [com.jsm.boardgame.holdem.domain.model.HandResult.shownSeatNos] 로, 폴드 승자는 본인이 보여 주기를 눌렀을 때만
 * (하우스 룰 10, `ShowHandService` 가 직전 결과의 `shownHands` 에 더한다) 채워진다(재구독 스냅샷에서는 저장된
 * 직전 결과에서 오는데, 그것도 같은 경로로 만든 것이다) — 쇼다운까지 간 좌석은
 * 전원 공개한다(사용자 결정, 머크 없음). 나열 순서만 TDA 17(마지막 라운드의 마지막 공격자부터, 없으면
 * 버튼 다음 좌석부터)을 따른다. 그 둘 외에 어떤 카드 성격의 필드도 이 파일에 추가하지 마라 — 추가하는
 * 순간 관전이 정보 유출이 된다.
 */
data class TablePublicView(
    val tableId: Long,
    val tableName: String,
    val handInProgress: Boolean,
    val street: String?,
    val board: List<String>,
    val pot: Long,
    /**
     * 끝난 스트리트까지 이미 가운데로 모인 칩의 팟 층. 지금 스트리트의 베팅은 넣지 않는다([pot] 에서 좌석별
     * 진행 중 베팅을 뺀 값). 메인 팟이 먼저고 폴드한 좌석은 자격에서 빠지며 콜 못 받은 초과분도 뺀다.
     * 핸드가 없거나 아직 모인 칩이 없으면 빈 리스트.
     */
    val pots: List<PotPublicView>,
    val toActSeatNo: Int?,
    val buttonSeatNo: Int?,
    /** 이번 핸드에서 실제로 SB·BB 를 낸 좌석. 핸드가 없으면 null(SB 는 dead small blind 여도 null). */
    val smallBlindSeatNo: Int?,
    val bigBlindSeatNo: Int?,
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
    /** 현재 차례 턴 타이머의 전체 길이 ms. [turnRemainingMs] 가 null 일 때만 null. */
    val turnTimeoutMs: Long?,
    /**
     * 테이블별 단조 증가 순번(TableViewSequence). 클라이언트 계약: 채널(공개/개인)별로 마지막
     * seq 를 기억하고 `seq < last` 인 메시지는 버린다(같으면 받는다. 같은 seq 는 항상 같은 상태다) — clientOutboundChannel 의
     * 다중 스레드 전송과 커밋 순서 역전 때문에 옛 스냅샷이 새 스냅샷보다 늦게 도착할 수 있다. 구독할 때
     * (재접속 포함) last 를 리셋한다.
     */
    val seq: Long,
)

data class PotPublicView(val amount: Long, val eligibleSeatNos: List<Int>)

data class SeatPublicView(
    val seatNo: Int,
    val userId: Long,
    val stack: Long,
    val totalContributed: Long,
    /** 지금 열린 베팅 라운드에서 낸 칩(좌석 앞 칩). 라운드가 없으면(런아웃·핸드 종료·핸드 미참가) 0. 가운데 칩은 [TablePublicView.pots], 콜 못 받은 초과분 = pot − Σpots − ΣstreetBet. */
    val streetBet: Long,
    /**
     * 핸드 참가 좌석은 핸드 안의 상태, 이번 핸드에 참가하지 않았으면(핸드가 없을 때 포함) `NOT_IN_HAND`.
     * 하우스 룰에 sit-out 은 없다. 참가하지 않는 이유는 BB 를 기다리는 중이라서일 수 있다([awaitingBigBlind]).
     */
    val status: String,
    /** 이 핸드에서 마지막으로 한 행동(FOLD/CHECK/CALL/BET/RAISE_TO). 행동 전·블라인드만 낸 좌석·핸드 미참가 좌석은 null. 핸드가 끝나도 다음 핸드 시작 전까지(결과 표시 중) 남는다. */
    val lastAction: String?,
    val presence: String,
    /** 이 핸드가 끝나면 일어나겠다고 예약한 좌석. 숨길 정보가 아니다. */
    val leaveReserved: Boolean,
    /** BB 가 이 좌석에 올 때까지 기다리는 중이라 `NOT_IN_HAND` 인 좌석. 숨길 정보가 아니다(카드 정보 없음). 핸드 참가 좌석은 false. */
    val awaitingBigBlind: Boolean,
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
 * 만들고, 폴드 승자가 자발적으로 보여 준 카드만 예외로 더해진다(하우스 룰 10). 폴드한 좌석·진행 중 핸드의 카드는 여기 오지 않는다.
 */
data class ShownHandView(val seatNo: Int, val userId: Long, val holeCards: List<String>, val category: String?)

/** 런아웃 중 공개된 좌석의 패 — [com.jsm.boardgame.holdem.domain.model.Hand.revealedSeatNos] 로만 만든다. */
data class RevealedHandView(val seatNo: Int, val holeCards: List<String>)
