package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalBettingActionException
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HandSnapshotTest {

    private fun chips(amount: Long) = Chips.of(amount)

    private fun stacksOf(vararg pairs: Pair<Int, Long>): Map<Int, Chips> = pairs.associate { (seat, amount) -> seat to chips(amount) }

    private fun sumStacks(seatNos: List<Int>, hand: Hand): Chips =
        seatNos.fold(Chips.ZERO) { acc, seatNo -> acc + hand.stackOf(seatNo) }

    /** 셔플하지 않는다 — Deck.FULL 순서 그대로 딜된다. 스냅샷→복원 후에도 같은 잔여 카드가
     * 같은 순서로 이어져야 원본과 복원본이 같은 미래 카드를 보게 된다. */
    private val identityShuffler = Shuffler { it }

    @Test
    fun `프리플랍 중간 상태에서 복원해도 다음 차례 좌석과 BB 옵션이 그대로다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        assertEquals(3, hand.toActSeatNo)

        val snapshot = hand.snapshot()
        val restored = Hand.reconstitute(snapshot, identityShuffler)

        assertEquals(3, restored.toActSeatNo)
        assertEquals(snapshot, restored.snapshot())

        // BB 옵션이 살아 있어야 체크로 라운드를 끝낼 수 있다.
        restored.act(3, BettingAction.Check)
        assertEquals(Street.FLOP, restored.street)
    }

    @Test
    fun `600으로 레이즈한 뒤 복원해도 다음 최소 레이즈는 1000이다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.RaiseTo(chips(600)))
        assertEquals(2, hand.toActSeatNo)

        val snapshot = hand.snapshot()
        val restored = Hand.reconstitute(snapshot, identityShuffler)

        assertEquals(snapshot, restored.snapshot())
        assertEquals(2, restored.toActSeatNo)
        val round = restored.snapshot().currentRound!!
        assertEquals(chips(600), round.currentBet)
        assertEquals(chips(1_000), round.currentBet + round.lastRaiseSize)
    }

    @Test
    fun `짧은 올인 뒤 복원하면 이미 행동한 좌석은 여전히 레이즈할 수 없다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 900), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.RaiseTo(chips(600))) // 풀 레이즈: 200 -> 600, 증분 400
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.RaiseTo(chips(900))) // 짧은 올인(스택 900): 600 -> 900, 증분 300 < 400, 안 열림

        assertEquals(SeatStatus.ALL_IN, hand.statusOf(3))
        assertEquals(chips(0), hand.stackOf(3))
        assertEquals(1, hand.toActSeatNo) // 이미 행동한 1번으로 한 바퀴 돌아옴

        val snapshot = hand.snapshot()
        val restored = Hand.reconstitute(snapshot, identityShuffler)

        assertEquals(snapshot, restored.snapshot())
        assertEquals(SeatStatus.ALL_IN, restored.statusOf(3))
        assertEquals(chips(0), restored.stackOf(3))

        val e = assertFailsWith<IllegalBettingActionException> { restored.act(1, BettingAction.RaiseTo(chips(1_300))) }
        assertEquals(HoldemErrorCode.RAISE_NOT_ALLOWED, e.errorCode)
    }

    @Test
    fun `폴드 상태·보드·홀카드가 복원 후에도 유지되고 포스트플랍 중간 상태도 복원된다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.Fold)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)

        assertEquals(Street.FLOP, hand.street)
        assertEquals(3, hand.board.size)

        val snapshot = hand.snapshot()
        val restored = Hand.reconstitute(snapshot, identityShuffler)

        assertEquals(snapshot, restored.snapshot())
        assertEquals(SeatStatus.FOLDED, restored.statusOf(1))
        assertEquals(hand.board, restored.board)
        assertEquals(hand.holeCardsOf(2), restored.holeCardsOf(2))
        assertEquals(hand.holeCardsOf(3), restored.holeCardsOf(3))
        assertEquals(2, restored.toActSeatNo)

        // 플랍 라운드 중간(한 액션 이후)에서 다시 한번 복원해도 구조가 그대로다.
        restored.act(2, BettingAction.Check)
        val midSnapshot = restored.snapshot()
        val restoredAgain = Hand.reconstitute(midSnapshot, identityShuffler)
        assertEquals(midSnapshot, restoredAgain.snapshot())
        assertEquals(3, restoredAgain.toActSeatNo)
    }

    @Test
    fun `복원된 덱은 이미 딜된 카드를 포함하지 않고 남은 장수가 맞는다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)

        val snapshot = hand.snapshot()
        val dealt = snapshot.holeCards.values.flatten() + snapshot.board
        val remaining = Deck.FULL - dealt

        assertEquals(Deck.FULL.size - dealt.size, remaining.size)
        assertTrue(dealt.none { it in remaining })

        val deck = Deck.reconstitute(identityShuffler, remaining)
        assertEquals(remaining.size, deck.remaining)
    }

    @Test
    fun `복원 후 핸드를 끝까지 진행해도 시작 스택 총합과 최종 스택 총합이 같다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)
        assertEquals(Street.FLOP, hand.street)

        val snapshot = hand.snapshot()
        val restored = Hand.reconstitute(snapshot, identityShuffler)

        // 플랍·턴·리버 각각 세 번씩(2, 3, 1 순서) 체크로 쇼다운까지 진행한다.
        repeat(3) {
            restored.act(2, BettingAction.Check)
            restored.act(3, BettingAction.Check)
            restored.act(1, BettingAction.Check)
        }

        assertTrue(restored.isFinished)
        val startingTotal = snapshot.startingStacks.values.fold(Chips.ZERO) { acc, c -> acc + c }
        assertEquals(startingTotal, sumStacks(listOf(1, 2, 3), restored))
    }

    @Test
    fun `같은 고정 덱에 같은 액션을 먹여도 중간에 스냅샷ㆍ복원을 거친 쪽과 안 거친 쪽의 최종 스택이 같다`() {
        val handA = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        var handB = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        handA.act(1, BettingAction.Call); handB.act(1, BettingAction.Call)
        handA.act(2, BettingAction.Call); handB.act(2, BettingAction.Call)
        handA.act(3, BettingAction.Check); handB.act(3, BettingAction.Check)

        handA.act(2, BettingAction.RaiseTo(chips(400)))
        handB.act(2, BettingAction.RaiseTo(chips(400)))

        // B만 여기서 스냅샷 -> 복원을 거친다. 이후 미래 카드(턴·리버)와 정산까지 A와 같아야 한다.
        handB = Hand.reconstitute(handB.snapshot(), identityShuffler)

        handA.act(3, BettingAction.Call); handB.act(3, BettingAction.Call)
        handA.act(1, BettingAction.Call); handB.act(1, BettingAction.Call)

        repeat(2) {
            handA.act(2, BettingAction.Check); handB.act(2, BettingAction.Check)
            handA.act(3, BettingAction.Check); handB.act(3, BettingAction.Check)
            handA.act(1, BettingAction.Check); handB.act(1, BettingAction.Check)
        }

        assertTrue(handA.isFinished)
        assertTrue(handB.isFinished)
        for (seatNo in listOf(1, 2, 3)) {
            assertEquals(handA.stackOf(seatNo), handB.stackOf(seatNo), "seatNo=$seatNo 최종 스택이 다르다")
        }
    }
}
