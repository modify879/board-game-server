package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.service.Shuffler

enum class Street { PREFLOP, FLOP, TURN, RIVER }

/**
 * @param payouts 좌석별 최종 수령액. 언콜드 벳 반환분도 포함한다
 * @param showdownRanks 쇼다운을 하지 않고 폴드로 끝났으면 빈 맵
 * @param showdownOrder 쇼다운 공개 순서(TDA 17: 마지막 라운드의 마지막 공격자부터, 없으면 버튼 다음 좌석부터).
 *   폴드로 끝났으면 빈 리스트
 */
data class HandResult(
    val payouts: Map<Int, Chips>,
    val pots: List<SidePot>,
    val showdownRanks: Map<Int, HandRank>,
    val showdownOrder: List<Int>,
) {
    /** 두 좌석 이상이 겨룬 팟에서 이긴 좌석. 반드시 [shownSeatNos] 에 포함된다(팟을 가져가려면 공개해야 한다, TDA 13). */
    val showdownWinners: Set<Int>
        get() {
            if (showdownRanks.isEmpty()) return emptySet()
            val winners = mutableSetOf<Int>()
            for (pot in pots) {
                if (pot.eligibleSeats.size < 2) continue
                val best = pot.eligibleSeats.maxOf { seat -> showdownRanks.getValue(seat) }
                winners += pot.eligibleSeats.filter { seat -> showdownRanks.getValue(seat) == best }
            }
            return winners
        }

    /**
     * 실제로 패를 공개하는 좌석. [showdownOrder] 를 따라가며 겨루는 팟(자격자 2명 이상)마다 판정한다 —
     * 그 팟에서 아직 아무도 공개하지 않았거나(TDA 17: 먼저 공개할 차례) 지금까지 그 팟에서 공개된
     * 최고 패를 이기거나 비기면 공개하고, 아니면 머크한다. 사이드팟은 팟마다 따로 판정한다(TDA 21).
     */
    val shownSeatNos: Set<Int>
        get() {
            val shown = mutableSetOf<Int>()
            val bestShownRankByPot = mutableMapOf<SidePot, HandRank>()
            for (seatNo in showdownOrder) {
                val rank = showdownRanks[seatNo] ?: continue
                var shows = false
                for (pot in pots) {
                    if (pot.eligibleSeats.size < 2 || seatNo !in pot.eligibleSeats) continue
                    val bestSoFar = bestShownRankByPot[pot]
                    if (bestSoFar == null || rank >= bestSoFar) {
                        shows = true
                        if (bestSoFar == null || rank > bestSoFar) bestShownRankByPot[pot] = rank
                    }
                }
                if (shows) shown += seatNo
            }
            check(showdownWinners.all { it in shown }) {
                "쇼다운 승자는 반드시 패를 공개해야 한다: winners=$showdownWinners, shown=$shown"
            }
            return shown
        }
}

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

    /**
     * 쇼다운 공개 순서(TDA 17)의 시작점 — 실제로 진행된 마지막 베팅 라운드의 마지막 공격자.
     * act() 마다 갱신되므로 마지막에 남는 값은 항상 "마지막으로 실제 진행된 라운드" 의 것이다 —
     * 전원 올인이라 건너뛴 라운드는 act() 자체가 없어 따로 처리할 필요가 없다. 그 라운드에
     * 벳/레이즈가 없었으면(체크로 넘어갔으면) null.
     */
    var showdownLeaderSeatNo: Int? = null
        private set

    val isFinished: Boolean get() = result != null

    val toActSeatNo: Int? get() = currentRound?.toActSeatNo

    fun availableActionsFor(seatNo: Int): AvailableActions? = currentRound?.availableActionsFor(seatNo)

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
        showdownLeaderSeatNo = showdownLeaderSeatNo,
    )

    fun act(seatNo: Int, action: BettingAction) {
        val round = currentRound
            ?: throw IllegalHandStateException(HoldemErrorCode.HAND_ALREADY_FINISHED, "이미 끝난 핸드입니다: buttonSeatNo=$buttonSeatNo")
        round.act(seatNo, action)
        // 라운드가 교체되기 전에 읽는다 — round 는 로컬 참조라 currentRound 필드가 바뀌어도 안전하다.
        showdownLeaderSeatNo = round.lastAggressorSeatNo
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
        finalizeResult(HandResult(payouts, layout.pots, emptyMap(), emptyList()))
    }

    private fun finishShowdown() {
        val layout = Pot.layout(totalContributed, foldedSeats())
        val contenders = statuses.filterValues { it != SeatStatus.FOLDED }.keys
        val ranks = contenders.associateWith { seatNo -> HandEvaluator.evaluate(holeCards.getValue(seatNo) + board) }
        val payouts = mergeUncalled(Pot.distribute(layout.pots, ranks, seatOrderFromButton()), layout)
        finalizeResult(HandResult(payouts, layout.pots, ranks, showdownOrder(contenders)))
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

    /** 버튼 다음 참가 좌석부터 시계 방향으로 좌석 번호 오름차순 순환 — 팟 분배 시 나머지 칩을 돌리는 순서. */
    private fun seatOrderFromButton(): List<Int> = seatOrderFromButton(seatNos, buttonSeatNo)

    /**
     * 쇼다운 공개 순서(TDA 17). [showdownLeaderSeatNo] 부터, 없으면(또는 그 좌석이 폴드했으면) 버튼
     * 다음 좌석부터, 시계 방향(좌석 번호 오름차순, 순환)으로 폴드하지 않은 좌석만 돈다.
     */
    private fun showdownOrder(liveSeats: Set<Int>): List<Int> {
        if (liveSeats.isEmpty()) return emptyList()
        val sorted = liveSeats.sorted()
        val startSeatNo = showdownLeaderSeatNo?.takeIf { it in liveSeats }
            ?: sorted.firstOrNull { it > buttonSeatNo } ?: sorted.first()
        val startIdx = sorted.indexOf(startSeatNo)
        return List(sorted.size) { i -> sorted[(startIdx + i) % sorted.size] }
    }

    companion object {
        /** 버튼 다음 참가 좌석부터 시계 방향(좌석 번호 오름차순, 순환). 버튼이 참가자가 아니어도(dead button)
         * 다음으로 큰 참가 좌석부터 시작해 정상 동작한다. */
        private fun seatOrderFromButton(seatNos: List<Int>, buttonSeatNo: Int): List<Int> {
            val startSeatNo = seatNos.firstOrNull { it > buttonSeatNo } ?: seatNos.first()
            val startIdx = seatNos.indexOf(startSeatNo)
            return List(seatNos.size) { i -> seatNos[(startIdx + i) % seatNos.size] }
        }

        fun start(
            stacks: Map<Int, Chips>,
            buttonSeatNo: Int,
            smallBlindSeatNo: Int?,
            bigBlindSeatNo: Int,
            smallBlind: Chips,
            bigBlind: Chips,
            shuffler: Shuffler,
            extraPostSeatNos: Set<Int> = emptySet(),
        ): Hand {
            if (stacks.size < 2 || bigBlindSeatNo !in stacks) {
                throw IllegalHandStateException(
                    HoldemErrorCode.NOT_ENOUGH_PLAYERS,
                    "참가자가 2명 이상이어야 하고 빅 블라인드 좌석이 참가자여야 합니다: seats=${stacks.keys}, bigBlindSeatNo=$bigBlindSeatNo",
                )
            }

            val seatNos = stacks.keys.sorted()

            // 불변식 가드 — 있을 수 없는 상태는 조용히 넘어가지 않고 바로 터뜨린다.
            if (seatNos.size == 2) {
                check(buttonSeatNo == smallBlindSeatNo) {
                    "헤즈업은 버튼이 SB 를 겸해야 한다: buttonSeatNo=$buttonSeatNo, smallBlindSeatNo=$smallBlindSeatNo"
                }
                check(buttonSeatNo in seatNos) { "버튼 좌석은 참가자여야 한다: buttonSeatNo=$buttonSeatNo, seatNos=$seatNos" }
                check(buttonSeatNo != bigBlindSeatNo) { "헤즈업에서 버튼이 BB 와 같을 수 없다: buttonSeatNo=$buttonSeatNo" }
            } else {
                check(buttonSeatNo != bigBlindSeatNo) { "버튼과 BB 는 같을 수 없다: buttonSeatNo=$buttonSeatNo, bigBlindSeatNo=$bigBlindSeatNo" }
                check(smallBlindSeatNo == null || (smallBlindSeatNo != buttonSeatNo && smallBlindSeatNo != bigBlindSeatNo)) {
                    "SB 는 버튼·BB 와 달라야 한다: smallBlindSeatNo=$smallBlindSeatNo, buttonSeatNo=$buttonSeatNo, bigBlindSeatNo=$bigBlindSeatNo"
                }
            }

            val deck = Deck.shuffled(shuffler)

            // 홀카드: 버튼 다음 참가 좌석(시계 방향)부터 한 장씩 두 바퀴 — 버튼이 마지막 카드를 받는다.
            val dealOrder = seatOrderFromButton(seatNos, buttonSeatNo)
            val holeCards = seatNos.associateWith { mutableListOf<Card>() }
            repeat(2) { dealOrder.forEach { seatNo -> holeCards.getValue(seatNo).add(deck.draw()) } }

            // 시계 방향(좌석 번호 오름차순, 순환)으로 다음 참가 좌석. from 이 참가자가 아니어도(dead button) 동작한다.
            fun nextSeatNo(from: Int): Int = seatNos.firstOrNull { it > from } ?: seatNos.first()

            val isHeadsUp = seatNos.size == 2
            val preflopFirstToActSeatNo: Int
            val postflopFirstToActSeatNo: Int
            if (isHeadsUp) {
                // 헤즈업 예외: 버튼이 SB 를 겸한다(포지션은 HoldemTable.advanceBlinds 가 이미 정했다).
                // 프리플랍은 버튼 먼저, 포스트플랍은 버튼이 나중.
                preflopFirstToActSeatNo = buttonSeatNo
                postflopFirstToActSeatNo = bigBlindSeatNo
            } else {
                preflopFirstToActSeatNo = nextSeatNo(bigBlindSeatNo)
                postflopFirstToActSeatNo = nextSeatNo(buttonSeatNo)
            }

            val bettingSeats = seatNos.map { seatNo -> BettingSeat(seatNo, stacks.getValue(seatNo), Chips.ZERO, SeatStatus.ACTIVE) }
            val preflopRound = BettingRound.preflop(bettingSeats, smallBlind, bigBlind, smallBlindSeatNo, bigBlindSeatNo, preflopFirstToActSeatNo, extraPostSeatNos)

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
                    lastAggressorSeatNo = round.lastAggressorSeatNo,
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
            hand.showdownLeaderSeatNo = snapshot.showdownLeaderSeatNo
            return hand
        }
    }
}
