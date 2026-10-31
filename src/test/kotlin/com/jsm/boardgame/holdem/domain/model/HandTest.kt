package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandTest {

    private fun chips(amount: Long) = Chips.of(amount)

    private fun stacksOf(vararg pairs: Pair<Int, Long>): Map<Int, Chips> = pairs.associate { (seat, amount) -> seat to chips(amount) }

    private fun sumStacks(seatNos: List<Int>, hand: Hand): Chips =
        seatNos.fold(Chips.ZERO) { acc, seatNo -> acc + hand.stackOf(seatNo) }

    /** [notation] 순서대로 카드를 앞에 두고, 나머지는 FULL 덱에서 중복 없이 채운다. 홀카드·보드를 통제하기 위한 고정 덱. */
    private fun fixedShuffler(vararg notation: String): Shuffler {
        val ordered = notation.map { Card.of(it) }
        return Shuffler { all -> ordered + all.filterNot { it in ordered } }
    }

    private val identityShuffler = Shuffler { it }

    @Test
    fun `3인 이상에서는 버튼 다음이 SB, 그다음이 BB, UTG가 프리플랍 첫 행동이고 포스트플랍은 버튼 다음 좌석부터다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        // 블라인드 포스팅 확인: SB=2, BB=3
        assertEquals(chips(9_900), hand.stackOf(2))
        assertEquals(chips(9_800), hand.stackOf(3))
        assertEquals(chips(10_000), hand.stackOf(1))
        assertEquals(1, hand.toActSeatNo) // UTG(=버튼 다음의 다음의 다음)가 프리플랍 첫 행동

        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)

        assertEquals(Street.FLOP, hand.street)
        assertEquals(2, hand.toActSeatNo) // 포스트플랍은 버튼(1) 다음 좌석부터
    }

    @Test
    fun `헤즈업은 프리플랍에서 버튼이 먼저 행동하고 플랍부터는 버튼이 나중에 행동한다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), identityShuffler)

        assertEquals(chips(9_900), hand.stackOf(1)) // 버튼이 SB를 겸한다
        assertEquals(chips(9_800), hand.stackOf(2))
        assertEquals(1, hand.toActSeatNo)

        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)

        assertEquals(Street.FLOP, hand.street)
        assertEquals(2, hand.toActSeatNo) // 포스트플랍은 BB(비-버튼)가 먼저
    }

    @Test
    fun `프리플랍에서 UTG와 SB가 폴드하면 BB가 블라인드를 가져가고 보드는 깔리지 않는다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.Fold)
        hand.act(2, BettingAction.Fold)

        assertTrue(hand.isFinished)
        assertTrue(hand.board.isEmpty())
        assertEquals(chips(300), hand.result!!.payouts.getValue(3))
        assertEquals(emptyMap(), hand.result!!.showdownRanks)
        assertEquals(emptySet(), hand.result!!.shownSeatNos) // 모두 폴드한 판은 공개하지 않는다
        assertEquals(chips(30_000), sumStacks(listOf(1, 2, 3), hand))
    }

    @Test
    fun `한 좌석이 폴드하고 나머지 둘이 쇼다운까지 가면 폴드한 좌석은 shownSeatNos 에 없고 나머지 둘은 있다`() {
        // 딜 순서(버튼(1) 다음인 2부터 시계방향, 버튼이 마지막): seat2=Kh,Kd(KK)  seat3=Ah,Ad(AA)  seat1=Qh,Qd(QQ, 프리플랍 폴드)  보드=2s,7d,9c,Tc,Jh
        val shuffler = fixedShuffler("Kh", "Ah", "Qh", "Kd", "Ad", "Qd", "2s", "7d", "9c", "Tc", "Jh")
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), shuffler)

        hand.act(1, BettingAction.Fold) // UTG(=버튼)가 프리플랍에서 폴드
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(3, BettingAction.Check)
        }

        assertTrue(hand.isFinished)
        assertEquals(setOf(2, 3), hand.result!!.shownSeatNos)
        assertTrue(1 !in hand.result!!.shownSeatNos)
    }

    @Test
    fun `끝난 핸드에 act 하면 HAND_ALREADY_FINISHED를 던진다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Fold)
        assertTrue(hand.isFinished)

        val e = assertFailsWith<IllegalHandStateException> { hand.act(2, BettingAction.Check) }
        assertEquals(HoldemErrorCode.HAND_ALREADY_FINISHED, e.errorCode)
    }

    @Test
    fun `참가자가 2명 미만이거나 빅 블라인드 좌석이 참가자가 아니면 NOT_ENOUGH_PLAYERS를 던진다`() {
        val onePlayer = assertFailsWith<IllegalHandStateException> {
            Hand.start(stacksOf(1 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = null, bigBlindSeatNo = 1, chips(100), chips(200), identityShuffler)
        }
        assertEquals(HoldemErrorCode.NOT_ENOUGH_PLAYERS, onePlayer.errorCode)

        val unknownBigBlind = assertFailsWith<IllegalHandStateException> {
            Hand.start(stacksOf(1 to 1_000, 2 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 5, chips(100), chips(200), identityShuffler)
        }
        assertEquals(HoldemErrorCode.NOT_ENOUGH_PLAYERS, unknownBigBlind.errorCode)
    }

    @Test
    fun `쇼다운까지 가면 고정된 패로 승자가 결정된다`() {
        // 딜 순서(버튼 다음부터 두 바퀴, 버튼이 마지막): seat2=Kh,Kd  seat1=Ah,Ad  보드=2s,7d,9c,Tc,Jh
        val shuffler = fixedShuffler("Kh", "Ah", "Kd", "Ad", "2s", "7d", "9c", "Tc", "Jh")
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), shuffler)

        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }

        assertTrue(hand.isFinished)
        assertEquals(listOf(Card.of("2s"), Card.of("7d"), Card.of("9c"), Card.of("Tc"), Card.of("Jh")), hand.board)
        assertEquals(HandCategory.PAIR, hand.result!!.showdownRanks.getValue(1).category)
        assertEquals(chips(10_200), hand.stackOf(1)) // AA가 KK를 이겨 팟(400) 전부를 가져간다
        assertEquals(chips(9_800), hand.stackOf(2))
        assertEquals(chips(20_000), sumStacks(listOf(1, 2), hand))
    }

    @Test
    fun `보드가 넛 스트레이트플러시라 두 좌석의 패가 같으면 팟을 반씩 나눈다`() {
        val shuffler = fixedShuffler("2c", "4d", "3c", "5d", "As", "Ks", "Qs", "Js", "Ts")
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), shuffler)

        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }

        assertTrue(hand.isFinished)
        assertEquals(hand.result!!.showdownRanks.getValue(1), hand.result!!.showdownRanks.getValue(2))
        assertEquals(chips(10_000), hand.stackOf(1)) // 정확히 반씩 나뉘어 시작 스택 그대로
        assertEquals(chips(10_000), hand.stackOf(2))
        assertEquals(chips(20_000), sumStacks(listOf(1, 2), hand))
    }

    @Test
    fun `숏스택이 프리플랍에서 올인하면 사이드팟이 생기고 메인팟과 사이드팟의 승자가 갈린다`() {
        // 딜 순서(버튼(1) 다음부터 두 바퀴): seat2=Kh,Kc(KK)  seat3=Qh,Qc(QQ)  seat1=Ah,Ac(AA)  보드=2d,7c,9h,Jc,4s(무늬·연속 없음)
        val shuffler = fixedShuffler("Kh", "Qh", "Ah", "Kc", "Qc", "Ac", "2d", "7c", "9h", "Jc", "4s")
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 5_000, 3 to 5_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), shuffler)

        hand.act(1, BettingAction.RaiseTo(chips(1_000))) // 숏스택 올인
        hand.act(2, BettingAction.RaiseTo(chips(3_000)))
        hand.act(3, BettingAction.Call)

        // 남은 두 좌석(2,3)만 이후 스트리트에서 계속 행동한다(1은 ALL_IN)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(3, BettingAction.Check)
        }

        assertTrue(hand.isFinished)
        assertEquals(2, hand.result!!.pots.size)
        assertEquals(setOf(1, 2, 3), hand.result!!.shownSeatNos) // 쇼다운까지 간 좌석은 전원 공개한다(머크 없음), QQ(3)도 포함
        assertEquals(chips(3_000), hand.stackOf(1)) // 메인팟(3,000) 승자 = AA
        assertEquals(chips(6_000), hand.stackOf(2)) // 사이드팟(4,000) 승자 = KK
        assertEquals(chips(2_000), hand.stackOf(3)) // QQ는 둘 다 못 이겨 못 가져간다
        assertEquals(chips(11_000), sumStacks(listOf(1, 2, 3), hand))
    }

    @Test
    fun `프리플랍 전원 올인이면 런아웃 상태가 되고 보드는 비어 있으며 참가자 패가 공개된다`() {
        val hand = preflopAllIn()

        assertTrue(hand.isRunningOut)
        assertFalse(hand.isFinished)
        assertEquals(0, hand.board.size)
        assertEquals(null, hand.toActSeatNo)
        assertEquals(setOf(1, 2, 3), hand.revealedSeatNos)
    }

    @Test
    fun `런아웃은 플랍 턴 리버를 한 걸음씩 깔고 그다음 걸음에 쇼다운으로 끝난다`() {
        val hand = preflopAllIn()

        hand.advanceRunout()
        assertEquals(3, hand.board.size)
        assertEquals(Street.FLOP, hand.street)
        hand.advanceRunout()
        assertEquals(4, hand.board.size)
        hand.advanceRunout()
        assertEquals(5, hand.board.size)
        assertEquals(Street.RIVER, hand.street)
        assertTrue(hand.isRunningOut)
        assertFalse(hand.isFinished)

        hand.advanceRunout()
        assertTrue(hand.isFinished)
        assertFalse(hand.isRunningOut)
        assertEquals(emptySet(), hand.revealedSeatNos)
        assertEquals(setOf(1, 2, 3), hand.result!!.showdownRanks.keys)
        assertEquals(chips(3_000), sumStacks(listOf(1, 2, 3), hand))
    }

    @Test
    fun `플랍 이후 올인이면 남은 스트리트 두 걸음 뒤 쇼다운한다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)
        hand.act(2, BettingAction.RaiseTo(chips(800)))
        hand.act(1, BettingAction.Call)

        assertTrue(hand.isRunningOut)
        assertEquals(3, hand.board.size)
        hand.advanceRunout()
        hand.advanceRunout()
        assertEquals(5, hand.board.size)
        assertTrue(hand.isRunningOut)
        hand.advanceRunout()
        assertTrue(hand.isFinished)
    }

    @Test
    fun `리버에서 올인이 콜되면 런아웃 없이 바로 쇼다운한다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)
        repeat(2) { // 플랍, 턴
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }
        assertEquals(Street.RIVER, hand.street)
        hand.act(2, BettingAction.RaiseTo(chips(800)))
        hand.act(1, BettingAction.Call)

        assertTrue(hand.isFinished)
        assertFalse(hand.isRunningOut)
        assertEquals(5, hand.board.size)
    }

    @Test
    fun `런아웃 중 베팅하면 BETTING_ROUND_CLOSED 다`() {
        val hand = preflopAllIn()

        val e = assertFailsWith<IllegalHandStateException> { hand.act(1, BettingAction.Check) }
        assertEquals(HoldemErrorCode.BETTING_ROUND_CLOSED, e.errorCode)
    }

    @Test
    fun `런아웃 중이 아닌 핸드를 진행하면 HAND_NOT_RUNNING_OUT 이다`() {
        val open = Hand.start(stacksOf(1 to 1_000, 2 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), identityShuffler)
        val e = assertFailsWith<IllegalHandStateException> { open.advanceRunout() }
        assertEquals(HoldemErrorCode.HAND_NOT_RUNNING_OUT, e.errorCode)

        val finished = preflopAllIn()
        while (finished.isRunningOut) finished.advanceRunout()
        val e2 = assertFailsWith<IllegalHandStateException> { finished.advanceRunout() }
        assertEquals(HoldemErrorCode.HAND_NOT_RUNNING_OUT, e2.errorCode)
    }

    @Test
    fun `런아웃 중 스냅샷을 복원해도 런아웃이며 끝까지 진행할 수 있다`() {
        val hand = preflopAllIn()
        hand.advanceRunout() // 플랍

        val restored = Hand.reconstitute(hand.snapshot(), identityShuffler)

        assertTrue(restored.isRunningOut)
        assertEquals(hand.board, restored.board)
        assertEquals(setOf(1, 2, 3), restored.revealedSeatNos)
        restored.advanceRunout()
        restored.advanceRunout()
        assertEquals(5, restored.board.size)
        assertEquals(5, restored.board.toSet().size)
        restored.advanceRunout()
        assertTrue(restored.isFinished)
        assertEquals(chips(3_000), sumStacks(listOf(1, 2, 3), restored))
    }

    @Test
    fun `라운드도 결과도 없지만 런아웃 플래그가 꺼진 스냅샷은 패를 공개하지 않고 베팅은 HAND_ALREADY_FINISHED 다`() {
        val snapshot = preflopAllIn().snapshot()
        assertTrue(snapshot.runningOut)

        val restored = Hand.reconstitute(snapshot.copy(runningOut = false), identityShuffler)

        assertFalse(restored.isRunningOut)
        assertEquals(emptySet(), restored.revealedSeatNos)
        val e = assertFailsWith<IllegalHandStateException> { restored.act(1, BettingAction.Check) }
        assertEquals(HoldemErrorCode.HAND_ALREADY_FINISHED, e.errorCode)
    }

    @Test
    fun `런아웃 플래그는 스냅샷 왕복에서 보존되고 쇼다운이 끝나면 내려간다`() {
        val hand = preflopAllIn()

        val snapshot = hand.snapshot()
        assertTrue(snapshot.runningOut)
        assertEquals(snapshot, Hand.reconstitute(snapshot, identityShuffler).snapshot())

        while (hand.isRunningOut) hand.advanceRunout()
        assertFalse(hand.snapshot().runningOut)
    }

    @Test
    fun `마지막 행동은 블라인드에는 없고 행동하면 남으며 스트리트가 넘어가도 남고 다시 행동하면 덮어쓴다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        assertEquals(listOf(null, null, null), listOf(1, 2, 3).map { hand.lastActionOf(it) })

        hand.act(1, BettingAction.RaiseTo(chips(600)))
        hand.act(2, BettingAction.Call)
        assertEquals(ActionType.RAISE_TO, hand.lastActionOf(1))
        assertEquals(ActionType.CALL, hand.lastActionOf(2))
        assertEquals(null, hand.lastActionOf(3))
        hand.act(3, BettingAction.Call)

        assertEquals(Street.FLOP, hand.street)
        assertEquals(listOf(ActionType.RAISE_TO, ActionType.CALL, ActionType.CALL), listOf(1, 2, 3).map { hand.lastActionOf(it) })
        hand.act(2, BettingAction.Check)
        assertEquals(ActionType.CHECK, hand.lastActionOf(2))
        assertEquals(ActionType.RAISE_TO, hand.lastActionOf(1))
    }

    private fun flopHand(): Hand {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.RaiseTo(chips(600)))
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Call)
        return hand
    }

    @Test
    fun `프리플랍 레이즈는 RAISE_TO 로 남고 아무도 안 건 스트리트의 첫 RaiseTo 는 BET, 그 뒤 재레이즈는 RAISE_TO 로 남는다`() {
        val hand = flopHand()
        assertEquals(ActionType.RAISE_TO, hand.lastActionOf(1))

        hand.act(2, BettingAction.RaiseTo(chips(400)))
        assertEquals(ActionType.BET, hand.lastActionOf(2))
        hand.act(3, BettingAction.RaiseTo(chips(1_000)))
        assertEquals(ActionType.RAISE_TO, hand.lastActionOf(3))
    }

    @Test
    fun `포스트플랍 올인 오프닝 베팅도 BET 으로 남고 스냅샷 왕복에서 보존된다`() {
        val hand = flopHand()
        hand.act(2, BettingAction.RaiseTo(chips(9_400)))

        assertEquals(ActionType.BET, hand.lastActionOf(2))
        assertEquals(ActionType.BET, Hand.reconstitute(hand.snapshot(), identityShuffler).lastActionOf(2))
    }

    @Test
    fun `시작할 때 받은 SB·BB 좌석을 그대로 갖고 dead small blind 면 SB 는 null 이다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        assertEquals(2, hand.smallBlindSeatNo)
        assertEquals(3, hand.bigBlindSeatNo)

        val dead = Hand.start(stacksOf(1 to 10_000, 3 to 10_000, 4 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = null, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        assertEquals(null, dead.smallBlindSeatNo)
        assertEquals(3, dead.bigBlindSeatNo)
    }

    @Test
    fun `폴드로 핸드가 끝나도 마지막 행동은 남는다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Fold)
        hand.act(2, BettingAction.Fold)

        assertTrue(hand.isFinished)
        assertEquals(ActionType.FOLD, hand.lastActionOf(1))
        assertEquals(ActionType.FOLD, hand.lastActionOf(2))
        assertEquals(null, hand.lastActionOf(3))
    }

    @Test
    fun `라운드가 런아웃으로 끝나도 마지막 행동은 남고 다음 스트리트를 깔아도 남는다`() {
        val hand = preflopAllIn()
        assertEquals(ActionType.RAISE_TO, hand.lastActionOf(1))
        assertEquals(ActionType.CALL, hand.lastActionOf(3))

        hand.advanceRunout()

        assertEquals(listOf(ActionType.RAISE_TO, ActionType.CALL, ActionType.CALL), listOf(1, 2, 3).map { hand.lastActionOf(it) })
    }

    @Test
    fun `프리플랍 시작 직후 SB·BB 의 이번 라운드 베팅액은 블라인드이고 나머지는 0이다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        assertEquals(chips(0), hand.streetBetOf(1))
        assertEquals(chips(100), hand.streetBetOf(2))
        assertEquals(chips(200), hand.streetBetOf(3))
    }

    @Test
    fun `프리플랍 라운드가 닫혀 플랍 라운드가 열리면 모두 0이다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)

        assertEquals(3, hand.board.size)
        assertTrue(hand.seatNos.all { hand.streetBetOf(it) == Chips.ZERO })
    }

    @Test
    fun `런아웃 중에는 열린 라운드가 없어 모두 0이다`() {
        val hand = preflopAllIn()

        assertTrue(hand.isRunningOut)
        assertTrue(hand.seatNos.all { hand.streetBetOf(it) == Chips.ZERO })
    }

    @Test
    fun `스트리트 중간에 총 기여액은 모인 칩과 이번 라운드 베팅액의 합이다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)
        hand.act(2, BettingAction.RaiseTo(chips(300)))

        assertEquals(chips(300), hand.streetBetOf(2))
        assertEquals(chips(0), hand.streetBetOf(3))
        // 프리플랍에 모인 칩은 좌석마다 200
        assertTrue(hand.seatNos.all { hand.totalContributedBy(it) == chips(200) + hand.streetBetOf(it) })
    }

    @Test
    fun `프리플랍에서 스트리트가 닫히기 전에는 모인 팟이 없다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Call)

        assertEquals(emptyList(), hand.collectedPots())
    }

    @Test
    fun `숏 올인이 있는 스트리트가 닫히면 메인 팟이 먼저고 사이드 팟은 긴 쪽만 자격이 있다`() {
        val hand = Hand.start(stacksOf(1 to 300, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.RaiseTo(chips(300)))
        hand.act(2, BettingAction.RaiseTo(chips(600)))
        hand.act(3, BettingAction.Call)

        assertEquals(
            listOf(SidePot(chips(900), setOf(1, 2, 3)), SidePot(chips(600), setOf(2, 3))),
            hand.collectedPots(),
        )
    }

    @Test
    fun `이번 스트리트에 폴드한 좌석은 바로 자격에서 빠지고 이번 스트리트 베팅은 모인 팟에 들어가지 않는다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)
        hand.act(2, BettingAction.RaiseTo(chips(200))) // 플랍 베팅 — 아직 모이지 않았다
        hand.act(3, BettingAction.Fold)

        assertEquals(Street.FLOP, hand.street)
        assertEquals(listOf(SidePot(chips(600), setOf(1, 2))), hand.collectedPots())
    }

    private fun preflopAllIn(): Hand {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.RaiseTo(chips(1_000)))
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Call)
        return hand
    }

    @Test
    fun `올인 콜이 모자라면 초과분은 언콜드 벳으로 레이저에게 그대로 돌아간다`() {
        // seat1=7c,2d(하이카드)  seat2=Ah,Ad(AA) — 쇼다운은 seat2가 이기지만 세팅상 콜 못 받은 4,000은 seat1에게 그대로 돌아간다
        val shuffler = fixedShuffler("Ah", "7c", "Ad", "2d", "9h", "Jc", "3s", "Kd", "4c")
        val hand = Hand.start(stacksOf(1 to 5_000, 2 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), shuffler)

        hand.act(1, BettingAction.RaiseTo(chips(5_000))) // 버튼 올인
        hand.act(2, BettingAction.Call) // BB는 1,000까지밖에 못 낸다
        while (hand.isRunningOut) hand.advanceRunout()

        assertTrue(hand.isFinished)
        assertEquals(chips(4_000), hand.stackOf(1)) // 콜 못 받은 4,000 반환 — 쇼다운에서 졌는데도 더 많이 남는다
        assertEquals(chips(2_000), hand.stackOf(2)) // 실제 팟(2,000)만 가져간다
        assertEquals(chips(6_000), sumStacks(listOf(1, 2), hand))
    }

    @Test
    fun `헤즈업에서 두 좌석 모두 블라인드로 올인이 되면 생성 시점에 런아웃 상태가 된다`() {
        // 딜 순서(버튼(SB) 다음인 BB부터, 버튼이 마지막): seat2(BB)=Kh,Kc(KK)  seat1(버튼/SB)=Ah,Ac(AA)  보드=2d,7c,9h,Jc,4s
        val shuffler = fixedShuffler("Kh", "Ah", "Kc", "Ac", "2d", "7c", "9h", "Jc", "4s")
        val hand = Hand.start(stacksOf(1 to 100, 2 to 200), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), shuffler)

        assertTrue(hand.isRunningOut)
        assertEquals(null, hand.toActSeatNo)
        assertEquals(setOf(1, 2), hand.revealedSeatNos)
        while (hand.isRunningOut) hand.advanceRunout()
        assertTrue(hand.isFinished)
        assertEquals(5, hand.board.size)
        assertTrue(1 in hand.result!!.showdownRanks)
        assertTrue(2 in hand.result!!.showdownRanks)
        assertEquals(chips(300), sumStacks(listOf(1, 2), hand)) // 시작 스택 총합(100+200) == 최종 스택 총합
    }

    @Test
    fun `헤즈업에서 SB 가 블라인드로 올인하고 BB 가 이미 콜 이상이면 BB 차례 없이 런아웃으로 간다`() {
        val hand = Hand.start(stacksOf(1 to 100, 2 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), identityShuffler)

        assertTrue(hand.isRunningOut)
        assertEquals(null, hand.toActSeatNo)
        while (hand.isRunningOut) hand.advanceRunout()
        assertTrue(hand.isFinished)
        assertEquals(chips(10_100), sumStacks(listOf(1, 2), hand))
    }

    @Test
    fun `올인 두 명과 남은 둘 중 한 명이 폴드해 한 명만 남으면 그 좌석 차례 없이 런아웃으로 가고 칩이 보존된다`() {
        // 버튼=2, SB=3, BB=4, UTG=1. 시드: 1=100, 2=300, 3=5000, 4=5000
        val hand = Hand.start(stacksOf(1 to 100, 2 to 300, 3 to 5_000, 4 to 5_000), buttonSeatNo = 2, smallBlindSeatNo = 3, bigBlindSeatNo = 4, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.Call) // 100 올인
        hand.act(2, BettingAction.RaiseTo(chips(300))) // 짧은 올인
        hand.act(3, BettingAction.RaiseTo(chips(1_000)))
        hand.act(4, BettingAction.Call)
        assertEquals(Street.FLOP, hand.street)
        hand.act(3, BettingAction.Fold) // 체크 가능한 폴드

        assertTrue(hand.isRunningOut)
        while (hand.isRunningOut) hand.advanceRunout()
        assertTrue(hand.isFinished)
        assertEquals(chips(10_400), sumStacks(listOf(1, 2, 3, 4), hand))
    }

    @Test
    fun `dead small blind 핸드는 아무도 SB 를 내지 않고 BB 는 정상 포스팅되며 currentBet 이 풀 빅블라인드다`() {
        val hand = Hand.start(
            stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000),
            buttonSeatNo = 3,
            smallBlindSeatNo = null,
            bigBlindSeatNo = 1,
            chips(100), chips(200),
            identityShuffler,
        )

        assertEquals(chips(10_000), hand.stackOf(2)) // SB 를 낼 사람이 없다
        assertEquals(chips(9_800), hand.stackOf(1))  // BB 는 정상 포스팅
        assertEquals(chips(10_000), hand.stackOf(3)) // 버튼도 포스팅하지 않는다
        assertEquals(chips(200), hand.potTotal())    // SB(100) 만큼 적다
        assertEquals(2, hand.toActSeatNo)            // 프리플랍 첫 행동 = BB(1) 다음의 참가 좌석
        // 스택 보존: 팟에 들어간 만큼만 스택 합이 줄어야 한다(칩이 생기거나 사라지지 않는다).
        assertEquals(chips(30_000), sumStacks(listOf(1, 2, 3), hand) + hand.potTotal())
    }

    @Test
    fun `dead button 이어도 핸드는 정상 진행되고 포스트플랍 첫 행동 좌석이 버튼 다음의 참가 좌석으로 정확히 정해진다`() {
        val hand = Hand.start(
            stacksOf(1 to 10_000, 2 to 10_000, 4 to 10_000),
            buttonSeatNo = 3, // 참가하지 않는 좌석(2와 4 사이에 비어 있다) — dead button
            smallBlindSeatNo = 4,
            bigBlindSeatNo = 1,
            chips(100), chips(200),
            identityShuffler,
        )

        assertEquals(chips(9_900), hand.stackOf(4))
        assertEquals(chips(9_800), hand.stackOf(1))
        assertEquals(2, hand.toActSeatNo) // 프리플랍 첫 행동 = BB(1) 다음의 참가 좌석

        hand.act(2, BettingAction.Call)
        hand.act(4, BettingAction.Call)
        hand.act(1, BettingAction.Check)

        assertEquals(Street.FLOP, hand.street)
        // 포스트플랍 첫 행동 = 버튼(3, 비어 있음) 다음의 참가 좌석. 좌석 번호 비교로 찾아야 한다 —
        // 리스트 인덱스 기반이면(버튼이 참가자가 아닐 때 indexOf가 -1이 되어) 틀린 값이 나온다.
        assertEquals(4, hand.toActSeatNo)
        // 스택 보존: 팟에 들어간 만큼만 스택 합이 줄어야 한다(칩이 생기거나 사라지지 않는다).
        assertEquals(chips(30_000), sumStacks(listOf(1, 2, 4), hand) + hand.potTotal())
    }

    @Test
    fun `3인 이상에서 버튼과 BB 가 같으면 예외를 던진다`() {
        assertFailsWith<IllegalStateException> {
            Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 1, chips(100), chips(200), identityShuffler)
        }
    }

    @Test
    fun `버튼이 가운데 좌석이어도 딜은 버튼 다음 좌석부터 시계방향으로 두 바퀴 돌고 버튼이 마지막 카드를 받는다`() {
        // 버튼=2. 딜 순서는 3(버튼 다음) -> 1 -> 2(버튼, 마지막). 두 바퀴 모두 같은 순서다.
        val shuffler = fixedShuffler("Ah", "2c", "3c", "Kh", "4c", "5c")
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 2, smallBlindSeatNo = 3, bigBlindSeatNo = 1, chips(100), chips(200), shuffler)

        assertEquals(listOf(Card.of("Ah"), Card.of("Kh")), hand.holeCardsOf(3)) // 버튼 다음 좌석 — 매 바퀴 첫 카드
        assertEquals(listOf(Card.of("2c"), Card.of("4c")), hand.holeCardsOf(1))
        assertEquals(listOf(Card.of("3c"), Card.of("5c")), hand.holeCardsOf(2)) // 버튼 — 매 바퀴 마지막 카드
    }

    @Test
    fun `헤즈업에서는 버튼이 SB 를 겸해 BB 가 첫 카드를, 버튼이 마지막 카드를 받는다`() {
        val shuffler = fixedShuffler("Ah", "2c", "Kh", "3c")
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), shuffler)

        assertEquals(listOf(Card.of("Ah"), Card.of("Kh")), hand.holeCardsOf(2)) // BB — 딜 전체에서 첫 카드
        assertEquals(listOf(Card.of("2c"), Card.of("3c")), hand.holeCardsOf(1)) // 버튼/SB — 딜 전체에서 마지막 카드
    }

    @Test
    fun `dead button 이어도 딜은 버튼 다음 참가 좌석부터 시작한다`() {
        // 버튼=3(참가하지 않는 좌석, 2와 4 사이). 딜 순서는 4(버튼 다음 참가 좌석) -> 1 -> 2.
        val shuffler = fixedShuffler("Ah", "2c", "3c", "Kh", "4c", "5c")
        val hand = Hand.start(
            stacksOf(1 to 10_000, 2 to 10_000, 4 to 10_000),
            buttonSeatNo = 3,
            smallBlindSeatNo = 4,
            bigBlindSeatNo = 1,
            chips(100), chips(200),
            shuffler,
        )

        assertEquals(listOf(Card.of("Ah"), Card.of("Kh")), hand.holeCardsOf(4)) // 버튼 다음 참가 좌석 — 매 바퀴 첫 카드
        assertEquals(listOf(Card.of("2c"), Card.of("4c")), hand.holeCardsOf(1))
        assertEquals(listOf(Card.of("3c"), Card.of("5c")), hand.holeCardsOf(2)) // 매 바퀴 마지막 카드
    }

    @Test
    fun `핸드가 끝나면 좌석별 스택 합이 시작 스택 합과 같다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 5_000, 3 to 7_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        hand.act(1, BettingAction.Fold)
        hand.act(2, BettingAction.Fold)

        assertTrue(hand.isFinished)
        assertEquals(chips(22_000), sumStacks(listOf(1, 2, 3), hand))
    }

    @Test
    fun `칩 합계가 시작 스택과 어긋난 스냅샷은 복원은 되지만 다음 act 가 IllegalStateException 을 던진다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 10_000, 3 to 10_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)
        val snapshot = hand.snapshot()
        val corrupted = snapshot.copy(startingStacks = snapshot.startingStacks + (1 to chips(15_000)))

        val restored = Hand.reconstitute(corrupted, identityShuffler)

        assertFailsWith<IllegalStateException> { restored.act(1, BettingAction.Call) }
    }

    @Test
    fun `헤즈업에서 BB 가 짧은 스택으로 올인하면 SB 는 행동 없이 곧바로 런아웃되고 BB 가 포스트한 100 만 걸린 팟이 된다`() {
        val hand = Hand.start(stacksOf(1 to 10_000, 2 to 100), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), identityShuffler)

        assertTrue(hand.isRunningOut)
        assertEquals(null, hand.toActSeatNo)

        while (hand.isRunningOut) hand.advanceRunout()

        val result = hand.result!!
        assertEquals(chips(200), result.pots.fold(Chips.ZERO) { acc, p -> acc + p.amount })
        assertEquals(chips(10_100), hand.stackOf(1) + hand.stackOf(2))
    }
}
