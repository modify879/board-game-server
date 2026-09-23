package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(chips(30_000), sumStacks(listOf(1, 2, 3), hand))
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
        // 딜 순서(오름차순 두 바퀴): seat1=Ah,Ad  seat2=Kh,Kd  보드=2s,7d,9c,Tc,Jh
        val shuffler = fixedShuffler("Ah", "Kh", "Ad", "Kd", "2s", "7d", "9c", "Tc", "Jh")
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
        // 딜 순서: seat1=Ah,Ac(AA)  seat2=Kh,Kc(KK)  seat3=Qh,Qc(QQ)  보드=2d,7c,9h,Jc,4s(무늬·연속 없음)
        val shuffler = fixedShuffler("Ah", "Kh", "Qh", "Ac", "Kc", "Qc", "2d", "7c", "9h", "Jc", "4s")
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
        assertEquals(chips(3_000), hand.stackOf(1)) // 메인팟(3,000) 승자 = AA
        assertEquals(chips(6_000), hand.stackOf(2)) // 사이드팟(4,000) 승자 = KK
        assertEquals(chips(2_000), hand.stackOf(3)) // QQ는 둘 다 못 이겨 못 가져간다
        assertEquals(chips(11_000), sumStacks(listOf(1, 2, 3), hand))
    }

    @Test
    fun `전원 올인이면 남은 보드가 한 번에 깔리고 바로 쇼다운한다`() {
        val hand = Hand.start(stacksOf(1 to 1_000, 2 to 1_000, 3 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, chips(100), chips(200), identityShuffler)

        hand.act(1, BettingAction.RaiseTo(chips(1_000)))
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Call)

        assertTrue(hand.isFinished)
        assertEquals(Street.RIVER, hand.street)
        assertEquals(5, hand.board.size)
        assertEquals(chips(3_000), sumStacks(listOf(1, 2, 3), hand))
    }

    @Test
    fun `올인 콜이 모자라면 초과분은 언콜드 벳으로 레이저에게 그대로 돌아간다`() {
        // seat1=7c,2d(하이카드)  seat2=Ah,Ad(AA) — 쇼다운은 seat2가 이기지만 세팅상 콜 못 받은 4,000은 seat1에게 그대로 돌아간다
        val shuffler = fixedShuffler("7c", "Ah", "2d", "Ad", "9h", "Jc", "3s", "Kd", "4c")
        val hand = Hand.start(stacksOf(1 to 5_000, 2 to 1_000), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), shuffler)

        hand.act(1, BettingAction.RaiseTo(chips(5_000))) // 버튼 올인
        hand.act(2, BettingAction.Call) // BB는 1,000까지밖에 못 낸다

        assertTrue(hand.isFinished)
        assertEquals(chips(4_000), hand.stackOf(1)) // 콜 못 받은 4,000 반환 — 쇼다운에서 졌는데도 더 많이 남는다
        assertEquals(chips(2_000), hand.stackOf(2)) // 실제 팟(2,000)만 가져간다
        assertEquals(chips(6_000), sumStacks(listOf(1, 2), hand))
    }

    @Test
    fun `헤즈업에서 두 좌석 모두 블라인드로 올인이 되면 생성 시점에 바로 쇼다운까지 끝난다`() {
        // 딜 순서: seat1(버튼/SB)=Ah,Ac(AA)  seat2(BB)=Kh,Kc(KK)  보드=2d,7c,9h,Jc,4s
        val shuffler = fixedShuffler("Ah", "Kh", "Ac", "Kc", "2d", "7c", "9h", "Jc", "4s")
        val hand = Hand.start(stacksOf(1 to 100, 2 to 200), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, chips(100), chips(200), shuffler)

        assertTrue(hand.isFinished)
        assertEquals(null, hand.toActSeatNo)
        assertEquals(5, hand.board.size)
        assertTrue(1 in hand.result!!.showdownRanks)
        assertTrue(2 in hand.result!!.showdownRanks)
        assertEquals(chips(300), sumStacks(listOf(1, 2), hand)) // 시작 스택 총합(100+200) == 최종 스택 총합
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
}
