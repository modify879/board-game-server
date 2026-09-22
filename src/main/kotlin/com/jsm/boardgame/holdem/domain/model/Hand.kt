package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.service.Shuffler

enum class Street { PREFLOP, FLOP, TURN, RIVER }

/**
 * @param payouts 좌석별 최종 수령액. 언콜드 벳 반환분도 포함한다
 * @param showdownRanks 쇼다운을 하지 않고 폴드로 끝났으면 빈 맵
 */
data class HandResult(
    val payouts: Map<Int, Chips>,
    val pots: List<SidePot>,
    val showdownRanks: Map<Int, HandRank>,
)

/**
 * 한 핸드(딜부터 정산까지)의 진행. 좌석·테이블은 이 안에 없다(3단계 이전) — 시작 스택과 버튼
 * 좌석 번호만 받는다. 포지션 계산은 [start] 에서 한 번 정해서 프리플랍/포스트플랍 각각의
 * 첫 행동 좌석으로 고정한다 — 스트리트마다 다시 계산하지 않는다.
 */
class Hand private constructor(
    val buttonSeatNo: Int,
    private val bigBlind: Chips,
    /** 이 핸드에 딜된 좌석. 핸드 도중 테이블에 새로 앉은 좌석은 여기에 없다. */
    val seatNos: List<Int>,
    private val holeCards: Map<Int, List<Card>>,
    private val deck: Deck,
    private val postflopFirstToActSeatNo: Int,
    /** 핸드 시작 시점 스택. 취소·환불이 이 값으로 되돌리는 것만으로 끝나도록 따로 둔다. */
    private val startingStacks: Map<Int, Chips>,
    private val stacks: MutableMap<Int, Chips>,
    private val statuses: MutableMap<Int, SeatStatus>,
    private val totalContributed: MutableMap<Int, Chips>,
    private var currentRound: BettingRound?,
) {
    private val boardCards = mutableListOf<Card>()

    var street: Street = Street.PREFLOP
        private set

    val board: List<Card> get() = boardCards.toList()

    var result: HandResult? = null
        private set

    val isFinished: Boolean get() = result != null

    val toActSeatNo: Int? get() = currentRound?.toActSeatNo

    fun holeCardsOf(seatNo: Int): List<Card> = holeCards.getValue(seatNo)

    /** 현재 스택. 라운드 진행 중이면 이번 라운드 커밋을 반영한 값, 핸드가 끝났으면 수령액까지 반영된 값. */
    fun stackOf(seatNo: Int): Chips =
        currentRound?.seats?.firstOrNull { it.seatNo == seatNo }?.stack ?: stacks.getValue(seatNo)

    /** 이 좌석의 베팅 상태. 공개 뷰가 폴드·올인 좌석을 표시하려면 필요하다. */
    fun statusOf(seatNo: Int): SeatStatus =
        currentRound?.seats?.firstOrNull { it.seatNo == seatNo }?.status ?: statuses.getValue(seatNo)

    fun totalContributedBy(seatNo: Int): Chips {
        val inFlight = currentRound?.seats?.firstOrNull { it.seatNo == seatNo }?.committed ?: Chips.ZERO
        return totalContributed.getValue(seatNo) + inFlight
    }

    fun potTotal(): Chips {
        val accumulated = totalContributed.values.fold(Chips.ZERO) { acc, c -> acc + c }
        val inFlight = currentRound?.seats?.fold(Chips.ZERO) { acc, s -> acc + s.committed } ?: Chips.ZERO
        return accumulated + inFlight
    }

    /**
     * 진행 중 핸드의 상태 스냅샷. 덱과 팟은 담지 않는다 — 덱은 저장하면 진행 중인 판의 미래 카드를
     * DB 접근자가 알게 되고(규칙 6), 팟은 좌석별 총 투입액에서 유도되므로 따로 저장하면 두 곳이
     * 어긋날 수 있다. 복원은 [Companion.reconstitute] 를 거친다.
     */
    fun snapshot(): HandSnapshot = HandSnapshot(
        buttonSeatNo = buttonSeatNo,
        bigBlind = bigBlind,
        seatNos = seatNos,
        street = street,
        board = board,
        holeCards = holeCards.mapValues { it.value.toList() },
        postflopFirstToActSeatNo = postflopFirstToActSeatNo,
        startingStacks = startingStacks.toMap(),
        stacks = stacks.toMap(),
        statuses = statuses.toMap(),
        totalContributed = totalContributed.toMap(),
        currentRound = currentRound?.snapshot(),
    )

    fun act(seatNo: Int, action: BettingAction) {
        val round = currentRound
            ?: throw IllegalHandStateException(HoldemErrorCode.HAND_ALREADY_FINISHED, "이미 끝난 핸드입니다: buttonSeatNo=$buttonSeatNo")
        round.act(seatNo, action)
        if (round.isComplete) {
            onRoundComplete(round)
        }
    }

    private fun onRoundComplete(round: BettingRound) {
        for (seat in round.seats) {
            totalContributed[seat.seatNo] = totalContributed.getValue(seat.seatNo) + seat.committed
            stacks[seat.seatNo] = seat.stack
            statuses[seat.seatNo] = seat.status
        }

        val notFolded = statuses.filterValues { it != SeatStatus.FOLDED }.keys
        if (notFolded.size == 1) {
            finishByFold()
            return
        }

        // 더 배팅할 사람이 없다(전원 올인 등) — 남은 보드를 한 번에 깔고 바로 쇼다운한다.
        val activeCount = statuses.values.count { it == SeatStatus.ACTIVE }
        if (activeCount <= 1) {
            dealRemainingBoardToRiver()
            street = Street.RIVER
            finishShowdown()
            return
        }

        if (street == Street.RIVER) {
            finishShowdown()
            return
        }

        street = nextStreet(street)
        dealBoardFor(street)
        val newSeats = seatNos.map { seatNo -> BettingSeat(seatNo, stacks.getValue(seatNo), Chips.ZERO, statuses.getValue(seatNo)) }
        currentRound = BettingRound.open(newSeats, bigBlind, postflopFirstToActSeatNo)
    }

    private fun nextStreet(current: Street): Street = when (current) {
        Street.PREFLOP -> Street.FLOP
        Street.FLOP -> Street.TURN
        Street.TURN -> Street.RIVER
        Street.RIVER -> error("리버 다음 스트리트는 없다")
    }

    // 번 카드는 두지 않는다 — 이미 셔플된 덱에서는 다음 장을 감추는 의미가 없다.
    private fun dealBoardFor(street: Street) {
        when (street) {
            Street.FLOP -> boardCards += deck.draw(3)
            Street.TURN, Street.RIVER -> boardCards += deck.draw(1)
            Street.PREFLOP -> error("프리플랍은 보드를 깔지 않는다")
        }
    }

    private fun dealRemainingBoardToRiver() {
        when (boardCards.size) {
            0 -> { dealBoardFor(Street.FLOP); dealBoardFor(Street.TURN); dealBoardFor(Street.RIVER) }
            3 -> { dealBoardFor(Street.TURN); dealBoardFor(Street.RIVER) }
            4 -> dealBoardFor(Street.RIVER)
        }
    }

    // 승자를 따로 받지 않는다 — 폴드 종료는 자격자 1명뿐인 팟이라 Pot.layout/distribute 가 그 좌석에 전액을 배정한다.
    private fun finishByFold() {
        val layout = Pot.layout(totalContributed, foldedSeats())
        val payouts = mergeUncalled(Pot.distribute(layout.pots, emptyMap(), seatOrderFromButton()), layout)
        finalizeResult(HandResult(payouts, layout.pots, emptyMap()))
    }

    private fun finishShowdown() {
        val layout = Pot.layout(totalContributed, foldedSeats())
        val contenders = statuses.filterValues { it != SeatStatus.FOLDED }.keys
        val ranks = contenders.associateWith { seatNo -> HandEvaluator.evaluate(holeCards.getValue(seatNo) + board) }
        val payouts = mergeUncalled(Pot.distribute(layout.pots, ranks, seatOrderFromButton()), layout)
        finalizeResult(HandResult(payouts, layout.pots, ranks))
    }

    private fun foldedSeats(): Set<Int> = statuses.filterValues { it == SeatStatus.FOLDED }.keys

    private fun mergeUncalled(base: Map<Int, Chips>, layout: PotLayout): Map<Int, Chips> {
        val seatNo = layout.uncalledSeatNo ?: return base
        if (!layout.uncalledAmount.isPositive()) return base
        val result = base.toMutableMap()
        result[seatNo] = (result[seatNo] ?: Chips.ZERO) + layout.uncalledAmount
        return result
    }

    private fun finalizeResult(handResult: HandResult) {
        for ((seatNo, payout) in handResult.payouts) {
            stacks[seatNo] = stacks.getValue(seatNo) + payout
        }
        result = handResult
        currentRound = null
    }

    /** 버튼 왼쪽부터 시계 방향으로 좌석 번호 오름차순 순환 — 팟 분배 시 나머지 칩을 돌리는 순서. */
    private fun seatOrderFromButton(): List<Int> {
        val buttonIdx = seatNos.indexOf(buttonSeatNo)
        return List(seatNos.size) { i -> seatNos[(buttonIdx + 1 + i) % seatNos.size] }
    }

    companion object {
        fun start(
            stacks: Map<Int, Chips>,
            buttonSeatNo: Int,
            smallBlind: Chips,
            bigBlind: Chips,
            shuffler: Shuffler,
        ): Hand {
            if (stacks.size < 2 || buttonSeatNo !in stacks) {
                throw IllegalHandStateException(
                    HoldemErrorCode.NOT_ENOUGH_PLAYERS,
                    "참가자가 2명 이상이어야 하고 버튼 좌석이 참가자여야 합니다: seats=${stacks.keys}, buttonSeatNo=$buttonSeatNo",
                )
            }

            val seatNos = stacks.keys.sorted()
            val deck = Deck.shuffled(shuffler)

            // 홀카드: 좌석 번호 오름차순으로 한 장씩 두 바퀴.
            val holeCards = seatNos.associateWith { mutableListOf<Card>() }
            repeat(2) { seatNos.forEach { seatNo -> holeCards.getValue(seatNo).add(deck.draw()) } }

            fun nextSeatNo(from: Int): Int = seatNos[(seatNos.indexOf(from) + 1) % seatNos.size]

            val isHeadsUp = seatNos.size == 2
            val sbSeatNo: Int
            val bbSeatNo: Int
            val preflopFirstToActSeatNo: Int
            val postflopFirstToActSeatNo: Int
            if (isHeadsUp) {
                // 헤즈업 예외: 버튼이 SB 를 겸한다. 프리플랍은 버튼 먼저, 포스트플랍은 버튼이 나중.
                sbSeatNo = buttonSeatNo
                bbSeatNo = nextSeatNo(buttonSeatNo)
                preflopFirstToActSeatNo = sbSeatNo
                postflopFirstToActSeatNo = bbSeatNo
            } else {
                sbSeatNo = nextSeatNo(buttonSeatNo)
                bbSeatNo = nextSeatNo(sbSeatNo)
                preflopFirstToActSeatNo = nextSeatNo(bbSeatNo)
                postflopFirstToActSeatNo = nextSeatNo(buttonSeatNo)
            }

            val bettingSeats = seatNos.map { seatNo -> BettingSeat(seatNo, stacks.getValue(seatNo), Chips.ZERO, SeatStatus.ACTIVE) }
            val preflopRound = BettingRound.preflop(bettingSeats, smallBlind, bigBlind, sbSeatNo, bbSeatNo, preflopFirstToActSeatNo)

            val hand = Hand(
                buttonSeatNo = buttonSeatNo,
                bigBlind = bigBlind,
                seatNos = seatNos,
                holeCards = holeCards,
                deck = deck,
                postflopFirstToActSeatNo = postflopFirstToActSeatNo,
                startingStacks = stacks,
                stacks = stacks.toMutableMap(),
                statuses = seatNos.associateWith { SeatStatus.ACTIVE }.toMutableMap(),
                totalContributed = seatNos.associateWith { Chips.ZERO }.toMutableMap(),
                currentRound = preflopRound,
            )
            // 블라인드가 스택 이상이면 포스팅 직후 전원 올인으로 라운드가 곧바로 끝나 있을 수 있다.
            // act() 를 거칠 사람이 없으므로(toActSeatNo == null) 여기서 직접 종료 처리를 태운다.
            if (preflopRound.isComplete) {
                hand.onRoundComplete(preflopRound)
            }
            return hand
        }

        /**
         * 영속 복원 전용 — 검증하지 않는다(매퍼 규약: `reconstitute()` 는 검증하지 않는다).
         * 진행 중(라운드가 열려 있는) 핸드만 대상이다 — 이미 끝난 핸드는 정산이 끝나 복구할 상태가
         * 없어 애초에 저장 대상이 아니다.
         * 덱은 스냅샷에 없다 — 이미 딜된 카드(홀카드+보드)를 뺀 나머지를 [shuffler] 로 새로 섞어
         * 다시 만든다. 아직 아무도 본 적 없는 카드라 어떤 순열이든 통계적으로 동일하다.
         */
        fun reconstitute(snapshot: HandSnapshot, shuffler: Shuffler): Hand {
            val dealtCards = snapshot.holeCards.values.flatten() + snapshot.board
            val remainingCards = Deck.FULL - dealtCards
            val deck = Deck.reconstitute(shuffler, remainingCards)

            val currentRound = snapshot.currentRound?.let { round ->
                BettingRound.reconstitute(
                    seats = round.seats.map { BettingSeat(it.seatNo, it.stack, it.committed, it.status) },
                    currentBet = round.currentBet,
                    lastRaiseSize = round.lastRaiseSize,
                    lastFullLevel = round.lastFullLevel,
                    actedSinceLastFullRaise = round.actedSinceLastFullRaise,
                    toActSeatNo = round.toActSeatNo,
                )
            }

            val hand = Hand(
                buttonSeatNo = snapshot.buttonSeatNo,
                bigBlind = snapshot.bigBlind,
                seatNos = snapshot.seatNos,
                holeCards = snapshot.holeCards,
                deck = deck,
                postflopFirstToActSeatNo = snapshot.postflopFirstToActSeatNo,
                startingStacks = snapshot.startingStacks,
                stacks = snapshot.stacks.toMutableMap(),
                statuses = snapshot.statuses.toMutableMap(),
                totalContributed = snapshot.totalContributed.toMutableMap(),
                currentRound = currentRound,
            )
            hand.street = snapshot.street
            hand.boardCards.addAll(snapshot.board)
            return hand
        }
    }
}
