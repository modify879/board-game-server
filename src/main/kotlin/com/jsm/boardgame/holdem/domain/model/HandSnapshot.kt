package com.jsm.boardgame.holdem.domain.model

/**
 * 진행 중 핸드의 상태 스냅샷. 이벤트 로그가 아니라 상태 스냅샷인 이유는 재생 엔진이 정상 경로와
 * 다른 코드라 거기서 틀리면 복구 시 돈이 틀어지고, 그 오류는 서버가 죽었을 때만 드러나기 때문이다.
 * [Hand.snapshot] 이 뽑고 [Hand.Companion.reconstitute] 가 되살린다.
 *
 * 담지 않는 것 — 둘 다 의도다:
 * - 덱: 저장하면 DB 접근자가 진행 중인 판의 미래 카드를 알게 된다(규칙 6). 복원 시 [Deck.FULL] 에서
 *   이미 딜된 카드(홀카드+보드)를 뺀 나머지를 새 [com.jsm.boardgame.holdem.domain.service.Shuffler] 로
 *   다시 섞는다. RNG 시드도 같은 이유로 담지 않는다
 * - 팟·사이드팟: 좌석별 총 투입액([totalContributed])에서 유도된다. 따로 저장하면 두 값이 어긋날 수 있다
 */
data class HandSnapshot(
    val buttonSeatNo: Int,
    val bigBlind: Chips,
    val seatNos: List<Int>,
    val street: Street,
    val board: List<Card>,
    val holeCards: Map<Int, List<Card>>,
    val postflopFirstToActSeatNo: Int,
    /** 핸드 시작 시점 스택. 취소·환불이 이 값으로 되돌리는 것만으로 끝나도록 따로 둔다. */
    val startingStacks: Map<Int, Chips>,
    val stacks: Map<Int, Chips>,
    val statuses: Map<Int, SeatStatus>,
    /** 핸드 전체(모든 스트리트 누적) 좌석별 투입액. */
    val totalContributed: Map<Int, Chips>,
    /** 라운드가 없으면(핸드가 끝났거나 아직 열리지 않았으면) null. */
    val currentRound: BettingRoundSnapshot?,
    /** 쇼다운 공개 순서(TDA 17)의 시작점. [Hand.showdownLeaderSeatNo] 그대로. */
    val showdownLeaderSeatNo: Int?,
) {
    data class BettingSeatSnapshot(
        val seatNo: Int,
        val stack: Chips,
        /** 이번 스트리트 투입액. */
        val committed: Chips,
        val status: SeatStatus,
    )

    data class BettingRoundSnapshot(
        val seats: List<BettingSeatSnapshot>,
        val currentBet: Chips,
        val lastRaiseSize: Chips,
        val lastFullLevel: Chips,
        /**
         * 이번 레벨에서 이미 행동한 좌석. 없으면 복구 후 BB 옵션이 사라지고, 이미 행동한 사람이
         * 다시 레이즈할 수 있게 된다.
         */
        val actedSinceLastFullRaise: Set<Int>,
        val toActSeatNo: Int?,
        /** 이 라운드의 마지막 공격자. [BettingRound.lastAggressorSeatNo] 그대로. */
        val lastAggressorSeatNo: Int?,
    )
}
