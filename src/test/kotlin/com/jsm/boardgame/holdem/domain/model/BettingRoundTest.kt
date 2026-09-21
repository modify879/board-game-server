package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalBettingActionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BettingRoundTest {

    private fun chips(amount: Long) = Chips.of(amount)

    private fun seat(seatNo: Int, stack: Long, committed: Long = 0, status: SeatStatus = SeatStatus.ACTIVE) =
        BettingSeat(seatNo, chips(stack), chips(committed), status)

    @Test
    fun `600으로 레이즈하면 증분 400이 적용되어 다음 최소 레이즈는 1000이다`() {
        val round = BettingRound.preflop(
            seats = listOf(seat(1, 100_000), seat(2, 100_000), seat(3, 100_000)),
            smallBlind = chips(100),
            bigBlind = chips(200),
            sbSeatNo = 1,
            bbSeatNo = 2,
            firstToActSeatNo = 3,
        )

        round.act(3, BettingAction.RaiseTo(chips(600)))

        assertEquals(chips(600), round.currentBet)
        assertEquals(chips(1_000), round.minRaiseTo)
    }

    @Test
    fun `풀 레이즈에 못 미치는 올인은 액션을 다시 열지 않아 이미 행동한 좌석은 레이즈할 수 없다`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000), seat(3, 100_000), seat(4, 300)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        round.act(1, BettingAction.Check)
        round.act(2, BettingAction.RaiseTo(chips(200))) // 풀 레이즈: 0 -> 200
        round.act(3, BettingAction.Call)
        round.act(4, BettingAction.RaiseTo(chips(300))) // 짧은 올인: 증분 100 < 200, 안 열림
        assertEquals(chips(300), round.currentBet)

        round.act(1, BettingAction.Call) // 한 바퀴 돌아 1번 다시 행동
        assertFalse(round.canRaise(2))

        val e = assertFailsWith<IllegalBettingActionException> { round.act(2, BettingAction.RaiseTo(chips(500))) }
        assertEquals(HoldemErrorCode.RAISE_NOT_ALLOWED, e.errorCode)
    }

    @Test
    fun `짧은 올인이 쌓여 풀 레이즈 이상이 되면 다시 열린다`() {
        val round = BettingRound.preflop(
            seats = listOf(seat(1, 100_000), seat(2, 100_000), seat(3, 300), seat(4, 100_000), seat(5, 400)),
            smallBlind = chips(100),
            bigBlind = chips(200),
            sbSeatNo = 1,
            bbSeatNo = 2,
            firstToActSeatNo = 3,
        )

        round.act(3, BettingAction.RaiseTo(chips(300))) // 짧은 올인, 증분 100 < 200, 안 열림
        round.act(4, BettingAction.Call)
        assertFalse(round.canRaise(4))

        round.act(5, BettingAction.RaiseTo(chips(400))) // 400 - 200(lastFullLevel) = 200 >= 200 -> 재오픈

        assertEquals(chips(400), round.currentBet)
        assertEquals(chips(600), round.minRaiseTo) // 400 + 200
        assertTrue(round.canRaise(4))
    }

    @Test
    fun `BB 옵션에서 전원 콜하면 체크로 라운드가 끝난다`() {
        val round = BettingRound.preflop(
            seats = listOf(seat(1, 100_000), seat(2, 100_000), seat(3, 100_000)),
            smallBlind = chips(100),
            bigBlind = chips(200),
            sbSeatNo = 1,
            bbSeatNo = 2,
            firstToActSeatNo = 3,
        )

        round.act(3, BettingAction.Call)
        round.act(1, BettingAction.Call)
        assertEquals(2, round.toActSeatNo)
        assertFalse(round.isComplete)

        round.act(2, BettingAction.Check)

        assertTrue(round.isComplete)
        assertNull(round.toActSeatNo)
    }

    @Test
    fun `BB는 옵션에서 레이즈할 수 있다`() {
        val round = BettingRound.preflop(
            seats = listOf(seat(1, 100_000), seat(2, 100_000), seat(3, 100_000)),
            smallBlind = chips(100),
            bigBlind = chips(200),
            sbSeatNo = 1,
            bbSeatNo = 2,
            firstToActSeatNo = 3,
        )

        round.act(3, BettingAction.Call)
        round.act(1, BettingAction.Call)

        round.act(2, BettingAction.RaiseTo(chips(600)))

        assertEquals(chips(600), round.currentBet)
        assertFalse(round.isComplete)
    }

    @Test
    fun `레이즈가 났으면 마지막 공격자까지 한 바퀴 돌아야 라운드가 끝난다`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000), seat(3, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        round.act(1, BettingAction.Check)
        round.act(2, BettingAction.RaiseTo(chips(200)))
        round.act(3, BettingAction.Call)

        assertEquals(1, round.toActSeatNo)
        assertFalse(round.isComplete)

        round.act(1, BettingAction.Call)

        assertTrue(round.isComplete)
    }

    @Test
    fun `전원 폴드면 즉시 라운드가 끝난다`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000), seat(3, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        round.act(1, BettingAction.Fold)
        round.act(2, BettingAction.Fold)

        assertTrue(round.isComplete)
        assertNull(round.toActSeatNo)
    }

    @Test
    fun `블라인드가 스택보다 크면 스택 전부를 내고 올인되지만 currentBet은 풀 빅블라인드다`() {
        val round = BettingRound.preflop(
            seats = listOf(seat(1, 100_000), seat(2, 100), seat(3, 100_000)),
            smallBlind = chips(100),
            bigBlind = chips(200),
            sbSeatNo = 1,
            bbSeatNo = 2,
            firstToActSeatNo = 3,
        )

        val bbSeat = round.seats.first { it.seatNo == 2 }
        assertEquals(SeatStatus.ALL_IN, bbSeat.status)
        assertEquals(chips(100), bbSeat.committed)
        assertEquals(chips(0), bbSeat.stack)
        assertEquals(chips(200), round.currentBet)
    }

    @Test
    fun `차례가 아닌 좌석이 행동하면 NOT_YOUR_TURN`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        val e = assertFailsWith<IllegalBettingActionException> { round.act(2, BettingAction.Check) }
        assertEquals(HoldemErrorCode.NOT_YOUR_TURN, e.errorCode)
    }

    @Test
    fun `베팅에 못 미친 채 체크하면 CANNOT_CHECK`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        round.act(1, BettingAction.RaiseTo(chips(200)))

        val e = assertFailsWith<IllegalBettingActionException> { round.act(2, BettingAction.Check) }
        assertEquals(HoldemErrorCode.CANNOT_CHECK, e.errorCode)
    }

    @Test
    fun `currentBet 이하로 레이즈하면 RAISE_TOO_SMALL`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        val e = assertFailsWith<IllegalBettingActionException> { round.act(1, BettingAction.RaiseTo(chips(0))) }
        assertEquals(HoldemErrorCode.RAISE_TOO_SMALL, e.errorCode)
    }

    @Test
    fun `최소 레이즈 미만이고 올인도 아니면 RAISE_TOO_SMALL`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        val e = assertFailsWith<IllegalBettingActionException> { round.act(1, BettingAction.RaiseTo(chips(100))) }
        assertEquals(HoldemErrorCode.RAISE_TOO_SMALL, e.errorCode)
    }

    @Test
    fun `레이즈에 필요한 칩이 스택을 초과하면 INSUFFICIENT_STACK`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100), seat(2, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        val e = assertFailsWith<IllegalBettingActionException> { round.act(1, BettingAction.RaiseTo(chips(200))) }
        assertEquals(HoldemErrorCode.INSUFFICIENT_STACK, e.errorCode)
    }

    @Test
    fun `라운드가 끝난 뒤 행동하면 BETTING_ROUND_CLOSED`() {
        val round = BettingRound.open(
            seats = listOf(seat(1, 100_000), seat(2, 100_000)),
            bigBlind = chips(200),
            firstToActSeatNo = 1,
        )

        round.act(1, BettingAction.Fold)
        assertTrue(round.isComplete)

        val e = assertFailsWith<IllegalBettingActionException> { round.act(2, BettingAction.Check) }
        assertEquals(HoldemErrorCode.BETTING_ROUND_CLOSED, e.errorCode)
    }
}
