package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalBettingActionException

enum class SeatStatus { ACTIVE, FOLDED, ALL_IN }

sealed interface BettingAction {
    data object Fold : BettingAction
    data object Check : BettingAction
    data object Call : BettingAction

    /** 이번 라운드 누적 투입액 기준 "얼마까지 올린다". */
    data class RaiseTo(val amount: Chips) : BettingAction
}

class BettingSeat(
    val seatNo: Int,
    stack: Chips,
    committed: Chips,
    status: SeatStatus,
) {
    var stack: Chips = stack
        private set

    /** 이번 라운드 투입액. */
    var committed: Chips = committed
        private set

    var status: SeatStatus = status
        private set

    internal fun commit(amount: Chips) {
        stack -= amount
        committed += amount
        if (stack.isZero() && status == SeatStatus.ACTIVE) {
            status = SeatStatus.ALL_IN
        }
    }

    internal fun fold() {
        status = SeatStatus.FOLDED
    }
}

/**
 * 한 스트리트의 베팅. 좌석 순서(누가 먼저 행동하는지, 헤즈업 예외)는 Hand 애그리거트가 정해서
 * [firstToActSeatNo] 로 넘긴다 — 여기서는 주어진 순서대로 시계 방향(좌석 번호 오름차순, 순환)으로만 돈다.
 */
class BettingRound private constructor(
    val seats: List<BettingSeat>,
    currentBet: Chips,
    lastRaiseSize: Chips,
    lastFullLevel: Chips,
    firstToActSeatNo: Int,
) {
    var currentBet: Chips = currentBet
        private set

    /** 마지막 풀 레이즈(또는 재오픈한 짧은 올인)의 증분. 다음 최소 레이즈 = currentBet + 이 값. */
    private var lastRaiseSize: Chips = lastRaiseSize

    /** 마지막으로 액션이 열린(풀 레이즈 또는 재오픈) 시점의 currentBet. 짧은 올인 누적분 재오픈 판정 기준선. */
    private var lastFullLevel: Chips = lastFullLevel

    /** 이 집합에 있으면 그 좌석은 지금 레벨에서 이미 행동했다 — 다시 레이즈할 수 없다. */
    private val actedSinceLastFullRaise = mutableSetOf<Int>()

    private var toAct: Int? = null

    init {
        toAct = if (isRoundComplete()) null else firstActiveSeatNoFrom(firstToActSeatNo)
    }

    /**
     * 영속 복원 전용 — 검증하지 않는다. actedSinceLastFullRaise·toAct 을 여기서 다시 계산하지 않고
     * 스냅샷 값을 그대로 되돌린다. 그렇지 않으면 BB 옵션이 사라지거나 이미 행동한 좌석이
     * 다시 레이즈할 수 있게 된다.
     */
    private constructor(
        seats: List<BettingSeat>,
        currentBet: Chips,
        lastRaiseSize: Chips,
        lastFullLevel: Chips,
        actedSinceLastFullRaise: Set<Int>,
        toAct: Int?,
    ) : this(seats, currentBet, lastRaiseSize, lastFullLevel, toAct ?: seats.firstOrNull()?.seatNo ?: 0) {
        this.actedSinceLastFullRaise.clear()
        this.actedSinceLastFullRaise.addAll(actedSinceLastFullRaise)
        this.toAct = toAct
    }

    val minRaiseTo: Chips get() = currentBet + lastRaiseSize
    val toActSeatNo: Int? get() = toAct
    val isComplete: Boolean get() = toAct == null

    fun callAmount(seatNo: Int): Chips {
        val seat = seatOf(seatNo)
        return Chips.min(currentBet - seat.committed, seat.stack)
    }

    fun canRaise(seatNo: Int): Boolean = seatNo !in actedSinceLastFullRaise

    /**
     * 진행 중 라운드 상태를 그대로 뽑는다. actedSinceLastFullRaise·toAct 까지 담아야
     * 복원 후에도 BB 옵션·짧은 올인 재오픈 금지가 깨지지 않는다.
     */
    fun snapshot(): HandSnapshot.BettingRoundSnapshot = HandSnapshot.BettingRoundSnapshot(
        seats = seats.map { HandSnapshot.BettingSeatSnapshot(it.seatNo, it.stack, it.committed, it.status) },
        currentBet = currentBet,
        lastRaiseSize = lastRaiseSize,
        lastFullLevel = lastFullLevel,
        actedSinceLastFullRaise = actedSinceLastFullRaise.toSet(),
        toActSeatNo = toAct,
    )

    fun act(seatNo: Int, action: BettingAction) {
        if (isComplete) {
            throw IllegalBettingActionException(HoldemErrorCode.BETTING_ROUND_CLOSED, "라운드가 이미 종료되었습니다")
        }
        if (toAct != seatNo) {
            throw IllegalBettingActionException(HoldemErrorCode.NOT_YOUR_TURN, "지금은 $seatNo 번 좌석의 차례가 아닙니다: toAct=$toAct")
        }
        val seat = seatOf(seatNo)
        when (action) {
            BettingAction.Fold -> seat.fold()
            BettingAction.Check -> check(seat)
            BettingAction.Call -> call(seat)
            is BettingAction.RaiseTo -> raiseTo(seat, action.amount)
        }
        toAct = if (isRoundComplete()) null else nextActiveSeatNoAfter(seatNo)
    }

    private fun check(seat: BettingSeat) {
        if (seat.committed != currentBet) {
            throw IllegalBettingActionException(
                HoldemErrorCode.CANNOT_CHECK,
                "커밋한 금액이 현재 베팅에 못 미쳐 체크할 수 없습니다: committed=${seat.committed}, currentBet=$currentBet",
            )
        }
        actedSinceLastFullRaise.add(seat.seatNo)
    }

    private fun call(seat: BettingSeat) {
        seat.commit(Chips.min(currentBet - seat.committed, seat.stack))
        actedSinceLastFullRaise.add(seat.seatNo)
    }

    private fun raiseTo(seat: BettingSeat, amount: Chips) {
        if (amount <= currentBet) {
            throw IllegalBettingActionException(HoldemErrorCode.RAISE_TOO_SMALL, "레이즈 금액이 현재 베팅 이하입니다: amount=$amount, currentBet=$currentBet")
        }
        val needed = amount - seat.committed
        if (needed > seat.stack) {
            throw IllegalBettingActionException(HoldemErrorCode.INSUFFICIENT_STACK, "레이즈에 필요한 칩이 스택을 초과합니다: needed=$needed, stack=${seat.stack}")
        }
        val isAllIn = needed == seat.stack
        if (amount < minRaiseTo && !isAllIn) {
            throw IllegalBettingActionException(HoldemErrorCode.RAISE_TOO_SMALL, "최소 레이즈 미만입니다: amount=$amount, minRaiseTo=$minRaiseTo")
        }
        if (!canRaise(seat.seatNo)) {
            throw IllegalBettingActionException(HoldemErrorCode.RAISE_NOT_ALLOWED, "이미 이번 레벨에서 행동해 다시 레이즈할 수 없습니다: seatNo=${seat.seatNo}")
        }

        seat.commit(needed)

        if (amount >= minRaiseTo) {
            // 자발적 풀 레이즈 — 액션을 완전히 새로 연다.
            lastRaiseSize = amount - currentBet
            currentBet = amount
            lastFullLevel = amount
            actedSinceLastFullRaise.clear()
            actedSinceLastFullRaise.add(seat.seatNo)
        } else {
            // 풀 레이즈에 못 미치는 올인. 짧은 올인이 누적되어 lastFullLevel 대비 풀 레이즈 이상 쌓이면 재오픈된다.
            currentBet = amount
            if (amount - lastFullLevel >= lastRaiseSize) {
                lastRaiseSize = amount - lastFullLevel
                lastFullLevel = amount
                actedSinceLastFullRaise.clear()
                actedSinceLastFullRaise.add(seat.seatNo)
            } else {
                actedSinceLastFullRaise.add(seat.seatNo)
            }
        }
    }

    private fun isRoundComplete(): Boolean {
        val contenders = seats.filter { it.status != SeatStatus.FOLDED }
        if (contenders.size <= 1) return true
        val activeSeats = seats.filter { it.status == SeatStatus.ACTIVE }
        if (activeSeats.isEmpty()) return true
        return activeSeats.all { it.committed == currentBet && it.seatNo in actedSinceLastFullRaise }
    }

    private fun activeSeatNosSorted(): List<Int> =
        seats.filter { it.status == SeatStatus.ACTIVE }.map { it.seatNo }.sorted()

    private fun firstActiveSeatNoFrom(seatNo: Int): Int? {
        val activeSeatNos = activeSeatNosSorted()
        if (activeSeatNos.isEmpty()) return null
        return activeSeatNos.firstOrNull { it >= seatNo } ?: activeSeatNos.first()
    }

    private fun nextActiveSeatNoAfter(seatNo: Int): Int? {
        val activeSeatNos = activeSeatNosSorted()
        if (activeSeatNos.isEmpty()) return null
        return activeSeatNos.firstOrNull { it > seatNo } ?: activeSeatNos.first()
    }

    private fun seatOf(seatNo: Int): BettingSeat = seats.first { it.seatNo == seatNo }

    companion object {
        /** 포스트플랍. currentBet = 0, 최소 베팅 = bigBlind. */
        fun open(seats: List<BettingSeat>, bigBlind: Chips, firstToActSeatNo: Int): BettingRound =
            BettingRound(
                seats = seats.sortedBy { it.seatNo },
                currentBet = Chips.ZERO,
                lastRaiseSize = bigBlind,
                lastFullLevel = Chips.ZERO,
                firstToActSeatNo = firstToActSeatNo,
            )

        /** 프리플랍. 블라인드를 여기서 포스팅한다. [sbSeatNo] 가 null 이면 아무도 스몰 블라인드를 내지 않는다
         * (dead small blind) — 그래도 currentBet 은 항상 full bigBlind 다. 블라인드가 스택보다 크면
         * 스택 전부를 내고 ALL_IN 이 되지만 currentBet 은 항상 full bigBlind 다.
         *
         * [extraPostSeatNos] 는 착석 시 "BB 즉시 포스팅"을 고른 좌석 — 첫 핸드에 한 번, BB 만큼 추가로
         * 라이브 벳으로 낸다(committed 에 반영, 스택에서 차감). 아직 행동한 적이 없으므로 BB 처럼
         * 옵션을 갖는다 — actedSinceLastFullRaise 에 넣지 않는다(생성 시점엔 항상 비어 있으므로 별도 처리 불필요).
         * 단순화: 실제 포커룸은 포스팅 금액의 일부를 죽은 돈(dead)으로 처리하기도 하지만, 우리는 전부
         * live 로 둔다 — 포스팅한 사람도 그 핸드에서 행동할 권리(체크/레이즈)를 그대로 갖는 편이 구현이
         * 간단하고, 사이드팟 계산도 "커밋된 돈은 전부 살아있다"는 기존 불변식 하나로 처리된다. */
        fun preflop(
            seats: List<BettingSeat>,
            smallBlind: Chips,
            bigBlind: Chips,
            sbSeatNo: Int?,
            bbSeatNo: Int,
            firstToActSeatNo: Int,
            extraPostSeatNos: Set<Int> = emptySet(),
        ): BettingRound {
            check(sbSeatNo !in extraPostSeatNos) { "추가 포스팅 좌석에 SB 가 포함될 수 없다: sbSeatNo=$sbSeatNo, extraPostSeatNos=$extraPostSeatNos" }
            check(bbSeatNo !in extraPostSeatNos) { "추가 포스팅 좌석에 BB 가 포함될 수 없다: bbSeatNo=$bbSeatNo, extraPostSeatNos=$extraPostSeatNos" }
            val sorted = seats.sortedBy { it.seatNo }
            if (sbSeatNo != null) {
                val sbSeat = sorted.first { it.seatNo == sbSeatNo }
                sbSeat.commit(Chips.min(smallBlind, sbSeat.stack))
            }
            val bbSeat = sorted.first { it.seatNo == bbSeatNo }
            bbSeat.commit(Chips.min(bigBlind, bbSeat.stack))

            for (seatNo in extraPostSeatNos) {
                val seat = sorted.first { it.seatNo == seatNo }
                seat.commit(Chips.min(bigBlind, seat.stack))
            }

            return BettingRound(
                seats = sorted,
                currentBet = bigBlind,
                lastRaiseSize = bigBlind,
                lastFullLevel = bigBlind,
                firstToActSeatNo = firstToActSeatNo,
            )
        }

        /**
         * 영속 복원 전용 — 검증하지 않는다. `open`/`preflop` 은 새 라운드를 여는 경로라 그대로 둔다.
         */
        fun reconstitute(
            seats: List<BettingSeat>,
            currentBet: Chips,
            lastRaiseSize: Chips,
            lastFullLevel: Chips,
            actedSinceLastFullRaise: Set<Int>,
            toActSeatNo: Int?,
        ): BettingRound = BettingRound(
            seats.sortedBy { it.seatNo },
            currentBet,
            lastRaiseSize,
            lastFullLevel,
            actedSinceLastFullRaise,
            toActSeatNo,
        )
    }
}
