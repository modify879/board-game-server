package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Hand

/**
 * 직전에 끝난 핸드의 공개 결과. 원시 타입만 담는다 — 홀카드는 [ShownHand] 로만, 즉
 * `HandResult.shownSeatNos` 에 든 좌석 것만 들어간다. 좌석 번호는 정산 직후 기립·재착석으로 주인이 바뀔 수
 * 있어 [Payout.userId]·[ShownHand.userId] 를 함께 담는다(핸드 시점의 점유자). 라이브 브로드캐스트와 재구독 스냅샷이 같은 출처를 쓴다.
 */
data class LastHandResult(val board: List<String>, val payouts: List<Payout>, val shownHands: List<ShownHand>) {
    data class Payout(val seatNo: Int, val userId: UserId, val amount: Long)
    data class ShownHand(val seatNo: Int, val userId: UserId, val holeCards: List<String>, val category: String)

    companion object {
        /**
         * [hand] 는 끝난 핸드여야 한다. [userIdBySeat] 는 정산이 기립을 반영하기 *전* 의 좌석별 점유자 —
         * 지급 받은 좌석과 공개한 좌석은 폴드하지 않아 그 시점에 반드시 점유돼 있다(없으면 즉시 실패한다).
         */
        fun of(hand: Hand, userIdBySeat: Map<Int, UserId>): LastHandResult {
            val result = checkNotNull(hand.result) { "끝나지 않은 핸드의 결과는 만들 수 없다" }
            return LastHandResult(
                board = hand.board.map(Card::toString),
                payouts = result.payouts
                    .filterValues { it.isPositive() }
                    .toSortedMap()
                    .map { (seatNo, amount) -> Payout(seatNo, userIdBySeat.getValue(seatNo), amount.amount) },
                shownHands = result.showdownOrder.filter { it in result.shownSeatNos }.map { seatNo ->
                    ShownHand(
                        seatNo = seatNo,
                        userId = userIdBySeat.getValue(seatNo),
                        holeCards = hand.holeCardsOf(seatNo).map(Card::toString),
                        category = result.showdownRanks.getValue(seatNo).category.name,
                    )
                },
            )
        }
    }
}
