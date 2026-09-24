package com.jsm.boardgame.holdem.presentation.ws.payload

import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

private val identityShuffler = Shuffler { it }

private val objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

/** [notation] 순서대로 카드를 앞에 두고, 나머지는 FULL 덱에서 중복 없이 채운다. 홀카드·보드를 통제하기 위한 고정 덱. */
private fun fixedShuffler(vararg notation: String): Shuffler {
    val ordered = notation.map { Card.of(it) }
    return Shuffler { all -> ordered + all.filterNot { it in ordered } }
}

private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): HoldemTable {
    val table = HoldemTable.create("assembler-test")
    for ((seatNo, buyIn) in buyIns) {
        table.sitDown(seatNo, userId = seatNo * 100L, buyIn = Chips.of(buyIn))
    }
    return table
}

class HoldemViewAssemblerTest {

    @Test
    fun `핸드가 없으면 공개 뷰는 진행 중이 아니고 좌석은 SITTING_OUT 이다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        val view = publicViewOf(TableId(1), table, null)

        assertFalse(view.handInProgress)
        assertNull(view.street)
        assertEquals(emptyList(), view.board)
        assertEquals(0L, view.pot)
        assertNull(view.toActSeatNo)
        assertNull(view.result)
        assertEquals(2, view.seats.size)
        assertTrue(view.seats.all { it.status == "SITTING_OUT" })
        assertTrue(view.seats.all { it.presence == "SEATED" })
        assertEquals(10_000L, view.seats.first { it.seatNo == 1 }.stack)
    }

    @Test
    fun `연결이 끊긴 좌석은 공개 뷰에 presence DISCONNECTED 로 나타난다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.markPresence(100, SeatPresence.DISCONNECTED)

        val view = publicViewOf(TableId(1), table, null)

        assertEquals("DISCONNECTED", view.seats.first { it.seatNo == 1 }.presence)
        assertEquals("SEATED", view.seats.first { it.seatNo == 2 }.presence)
    }

    @Test
    fun `좌석 A 의 개인 뷰에는 좌석 B 의 카드가 들어가지 않는다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
        // 버튼=1(SB) 이라 딜은 2(BB)부터 시작한다 — 1=2s,4s 를 유지하려면 2보다 먼저 딜에 놓아야 한다.
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            fixedShuffler("3s", "2s", "5s", "4s"),
        )

        val viewA = privateViewOf(TableId(1), 1, hand)
        val viewB = privateViewOf(TableId(1), 2, hand)

        assertEquals(listOf("2s", "4s"), viewA.holeCards)
        assertEquals(listOf("3s", "5s"), viewB.holeCards)
        assertTrue(viewA.holeCards.none { it in viewB.holeCards })
    }

    @Test
    fun `폴드한 좌석은 공개 뷰에서 FOLDED 로 표시되고 핸드는 계속 진행 중이며 홀카드가 노출되지 않는다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        table.moveButtonToNextOccupiedSeat() // null -> 1
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 2,
            bigBlindSeatNo = 3,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        val toAct = hand.toActSeatNo!!
        val holeCardTokens = hand.seatNos.flatMap { hand.holeCardsOf(it) }.map { it.toString() }

        hand.act(toAct, BettingAction.Fold)

        val view = publicViewOf(TableId(1), table, hand)
        assertFalse(hand.isFinished)
        assertTrue(view.handInProgress)
        assertEquals("FOLDED", view.seats.first { it.seatNo == toAct }.status)
        assertEquals(emptyList(), view.board) // 아직 프리플랍
        assertNull(view.result) // 핸드가 아직 안 끝났다

        val json = objectMapper.writeValueAsString(view)
        for (card in holeCardTokens) {
            assertFalse(json.contains("\"$card\""), "진행 중 핸드의 홀카드 $card 가 공개 뷰 직렬화 결과에 노출됨: $json")
        }
    }

    @Test
    fun `블라인드만으로 양쪽이 올인되면 공개 뷰에 ALL_IN 과 최종 보드가 담긴다`() {
        val table = tableWithSeats(1 to 8_000L, 2 to 8_000L)
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(100), 2 to Chips.of(100)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )

        assertTrue(hand.isFinished)
        val view = publicViewOf(TableId(1), table, hand)
        assertFalse(view.handInProgress)
        assertNull(view.toActSeatNo)
        assertTrue(view.seats.all { it.status == "ALL_IN" })
        assertEquals(5, view.board.size) // 리버까지 다 깔렸다
    }

    @Test
    fun `폴드로 끝난 핸드의 공개 뷰는 이긴 좌석만 담고 shownHands 는 비어 있으며 어느 좌석의 홀카드도 노출되지 않는다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        val holeCardTokens = (hand.holeCardsOf(1) + hand.holeCardsOf(2)).map { it.toString() }

        hand.act(hand.toActSeatNo!!, BettingAction.Fold)

        val view = publicViewOf(TableId(1), table, hand)

        val result = view.result!!
        assertEquals(listOf(PayoutPublicView(seatNo = 2, amount = 300)), result.payouts)
        assertEquals(emptyList(), result.shownHands)

        val json = objectMapper.writeValueAsString(view)
        for (card in holeCardTokens) {
            assertFalse(json.contains("\"$card\""), "카드 $card 가 공개 뷰 직렬화 결과에 노출됨: $json")
        }
    }

    @Test
    fun `리버에 A 가 베팅하고 B 가 콜해 B 가 이기면 둘 다 공개된다(A 가 순서상 먼저)`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
        // 딜 순서(버튼(1) 다음인 2부터, 버튼이 마지막): seat2=Ah,Ad(AA)  seat1=Kh,Kd(KK)  보드=2s,7d,9c,Tc,Jh
        val shuffler = fixedShuffler("Ah", "Kh", "Ad", "Kd", "2s", "7d", "9c", "Tc", "Jh")
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            shuffler,
        )
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check) // 프리플랍 종료
        repeat(2) { // 플랍·턴 체크로 통과
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }
        hand.act(2, BettingAction.Check) // 리버: B(BB, postflop 선첫)가 먼저 체크
        hand.act(1, BettingAction.RaiseTo(Chips.of(200))) // A(버튼/SB)가 베팅
        hand.act(2, BettingAction.Call) // B가 콜 -> 쇼다운

        assertEquals(1, hand.showdownLeaderSeatNo)
        val view = publicViewOf(TableId(1), table, hand)

        val result = view.result!!
        assertEquals(
            listOf(
                ShownHandView(seatNo = 1, holeCards = listOf("Kh", "Kd"), category = "PAIR"),
                ShownHandView(seatNo = 2, holeCards = listOf("Ah", "Ad"), category = "PAIR"),
            ),
            result.shownHands,
        )
    }

    @Test
    fun `리버에 A 가 베팅하고 B 가 콜했는데 A 가 이기면 A 만 공개되고 B 는 머크한다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
        // 딜 순서(버튼(1) 다음인 2부터, 버튼이 마지막): seat2=Kh,Kd(KK)  seat1=Ah,Ad(AA)  보드=2s,7d,9c,Tc,Jh
        val shuffler = fixedShuffler("Kh", "Ah", "Kd", "Ad", "2s", "7d", "9c", "Tc", "Jh")
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            shuffler,
        )
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)
        repeat(2) {
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }
        hand.act(2, BettingAction.Check)
        hand.act(1, BettingAction.RaiseTo(Chips.of(200)))
        hand.act(2, BettingAction.Call)

        assertEquals(1, hand.showdownLeaderSeatNo)
        val view = publicViewOf(TableId(1), table, hand)
        val json = objectMapper.writeValueAsString(view)

        val result = view.result!!
        assertEquals(
            listOf(ShownHandView(seatNo = 1, holeCards = listOf("Ah", "Ad"), category = "PAIR")),
            result.shownHands,
        )
        assertTrue(json.contains("\"Ah\""))
        assertTrue(json.contains("\"Ad\""))
        for (card in listOf("Kh", "Kd")) {
            assertFalse(json.contains("\"$card\""), "머크한 좌석의 홀카드 $card 가 공개 뷰 직렬화 결과에 노출됨: $json")
        }
    }

    @Test
    fun `리버 베팅 없이 체크로 끝나면 공개 순서는 버튼 왼쪽부터라 진 좌석도 먼저 보여줄 차례면 공개된다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat() // button=1(=SB), BB=2
        // 딜 순서(버튼(1) 다음인 2부터, 버튼이 마지막): seat2=Kh,Kd(KK)  seat1=Ah,Ad(AA)  보드=2s,7d,9c,Tc,Jh — 전부 체크로 통과
        val shuffler = fixedShuffler("Kh", "Ah", "Kd", "Ad", "2s", "7d", "9c", "Tc", "Jh")
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            shuffler,
        )
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }

        assertNull(hand.showdownLeaderSeatNo) // 리버에 벳/레이즈가 없었다
        val view = publicViewOf(TableId(1), table, hand)

        val result = view.result!!
        // 버튼(1) 다음 좌석(2, BB)부터 공개 — KK 인 2가 먼저 보여주고 나면, AA 인 1이 이겨서 뒤이어 보여준다.
        assertEquals(
            listOf(
                ShownHandView(seatNo = 2, holeCards = listOf("Kh", "Kd"), category = "PAIR"),
                ShownHandView(seatNo = 1, holeCards = listOf("Ah", "Ad"), category = "PAIR"),
            ),
            result.shownHands,
        )
    }

    @Test
    fun `삼자 체크다운에서 첫 공개가 약한 패, 다음이 이를 이기면 셋 다 공개된다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        table.moveButtonToNextOccupiedSeat() // button=1, SB=2, BB=3
        // 딜 순서(버튼(1) 다음인 2부터 시계방향, 버튼이 마지막): seat2=Jh,Jd(JJ)  seat3=Qh,Qd(QQ)  seat1=Kh,Kd(KK)  보드=2d,7c,9h,4s,5c
        val shuffler = fixedShuffler("Jh", "Qh", "Kh", "Jd", "Qd", "Kd", "2d", "7c", "9h", "4s", "5c")
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 2,
            bigBlindSeatNo = 3,
            table.smallBlind,
            table.bigBlind,
            shuffler,
        )
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(3, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }

        assertNull(hand.showdownLeaderSeatNo)
        val view = publicViewOf(TableId(1), table, hand)

        val result = view.result!!
        // 버튼(1) 다음부터: 2(JJ, 약한 패, 먼저 공개) -> 3(QQ, JJ를 이겨 공개) -> 1(KK, 둘 다 이겨 공개)
        assertEquals(
            listOf(
                ShownHandView(seatNo = 2, holeCards = listOf("Jh", "Jd"), category = "PAIR"),
                ShownHandView(seatNo = 3, holeCards = listOf("Qh", "Qd"), category = "PAIR"),
                ShownHandView(seatNo = 1, holeCards = listOf("Kh", "Kd"), category = "PAIR"),
            ),
            result.shownHands,
        )
    }

    @Test
    fun `삼자 체크다운에서 두 번째 패가 첫 번째보다 약하면 두 번째는 공개되지 않는다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        table.moveButtonToNextOccupiedSeat() // button=1, SB=2, BB=3
        // 딜 순서(버튼(1) 다음인 2부터 시계방향, 버튼이 마지막): seat2=Qh,Qd(QQ)  seat3=Jh,Jd(JJ)  seat1=Kh,Kd(KK)  보드=2d,7c,9h,4s,5c
        val shuffler = fixedShuffler("Qh", "Jh", "Kh", "Qd", "Jd", "Kd", "2d", "7c", "9h", "4s", "5c")
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 2,
            bigBlindSeatNo = 3,
            table.smallBlind,
            table.bigBlind,
            shuffler,
        )
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Check)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(3, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }

        assertNull(hand.showdownLeaderSeatNo)
        val view = publicViewOf(TableId(1), table, hand)
        val json = objectMapper.writeValueAsString(view)

        val result = view.result!!
        // 버튼(1) 다음부터: 2(QQ, 먼저 공개) -> 3(JJ, QQ 보다 약해 머크) -> 1(KK, QQ를 이겨 공개)
        assertEquals(
            listOf(
                ShownHandView(seatNo = 2, holeCards = listOf("Qh", "Qd"), category = "PAIR"),
                ShownHandView(seatNo = 1, holeCards = listOf("Kh", "Kd"), category = "PAIR"),
            ),
            result.shownHands,
        )
        for (card in listOf("Jh", "Jd")) {
            assertFalse(json.contains("\"$card\""), "머크한 좌석(JJ)의 홀카드 $card 가 공개 뷰 직렬화 결과에 노출됨: $json")
        }
    }

    @Test
    fun `숏스택 올인의 메인팟은 자신만, 사이드팟은 먼저 겨루는 좌석부터 판정된다`() {
        // 버튼=3 이라 리더가 null 로 리셋돼도(포스트플랍 전부 체크) 공개 순서는 버튼 다음인 1부터 시작한다.
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        // 딜 순서: seat1=Ah,Ac(AA, 숏스택 올인)  seat2=Kh,Kc(KK)  seat3=Qh,Qc(QQ)  보드=2d,7c,9h,Jc,4s
        val shuffler = fixedShuffler("Ah", "Kh", "Qh", "Ac", "Kc", "Qc", "2d", "7c", "9h", "Jc", "4s")
        val hand = Hand.start(
            mapOf(1 to Chips.of(1_000), 2 to Chips.of(5_000), 3 to Chips.of(5_000)),
            buttonSeatNo = 3,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            smallBlind = Chips.of(100),
            bigBlind = Chips.of(200),
            shuffler = shuffler,
        )

        hand.act(3, BettingAction.Call) // UTG(=버튼) 콜
        hand.act(1, BettingAction.RaiseTo(Chips.of(1_000))) // 숏스택(SB) 올인
        hand.act(2, BettingAction.RaiseTo(Chips.of(3_000))) // BB 재레이즈 -> 사이드팟 경계
        hand.act(3, BettingAction.Call)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(3, BettingAction.Check)
        }

        assertNull(hand.showdownLeaderSeatNo) // 포스트플랍 전부 체크
        val view = publicViewOf(TableId(1), table, hand)
        val json = objectMapper.writeValueAsString(view)

        val result = view.result!!
        // 공개 순서 1,2,3: 1(AA)은 메인팟만 자격 -> 공개. 2(KK)는 사이드팟을 처음 겨뤄 공개.
        // 3(QQ)은 사이드팟에서 2(KK)보다 약해 머크.
        assertEquals(
            listOf(
                ShownHandView(seatNo = 1, holeCards = listOf("Ah", "Ac"), category = "PAIR"),
                ShownHandView(seatNo = 2, holeCards = listOf("Kh", "Kc"), category = "PAIR"),
            ),
            result.shownHands,
        )
        for (card in listOf("Qh", "Qc")) {
            assertFalse(json.contains("\"$card\""), "머크한 좌석(QQ)의 홀카드 $card 가 공개 뷰 직렬화 결과에 노출됨: $json")
        }
    }

    @Test
    fun `차례인 좌석의 개인 뷰에는 availableActions 가 담기고 다른 좌석은 null 이다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 2,
            bigBlindSeatNo = 3,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        val toAct = hand.toActSeatNo!!

        val actingView = privateViewOf(TableId(1), toAct, hand)
        val otherSeatNo = hand.seatNos.first { it != toAct }
        val otherView = privateViewOf(TableId(1), otherSeatNo, hand)

        assertTrue(actingView.availableActions != null)
        assertNull(otherView.availableActions)
    }

    @Test
    fun `핸드가 끝나면 개인 뷰의 availableActions 는 null 이다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        hand.act(hand.toActSeatNo!!, BettingAction.Fold)

        val view = privateViewOf(TableId(1), 1, hand)

        assertNull(view.availableActions)
    }
}
