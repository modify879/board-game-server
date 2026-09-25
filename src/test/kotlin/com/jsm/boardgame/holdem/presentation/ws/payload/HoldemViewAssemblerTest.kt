package com.jsm.boardgame.holdem.presentation.ws.payload

import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import java.time.Instant
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
    fun `리버에 A 가 베팅하고 B 가 콜해 B 가 이기면 B 만 자동 공개되고 A 가 SHOW 를 고르면 둘 다 공개 순서대로 노출된다`() {
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
        hand.act(2, BettingAction.Call) // B가 콜 -> 쇼다운, B(AA)가 이긴다

        assertEquals(1, hand.showdownLeaderSeatNo)
        hand.openReveal(Instant.parse("2026-01-01T00:00:10Z"))

        // 이긴 B(2)만 자동 공개 — 진 A(1)는 아직 선택하지 않아 빠진다.
        assertEquals(
            listOf(ShownHandView(seatNo = 2, holeCards = listOf("Ah", "Ad"), category = "PAIR")),
            publicViewOf(TableId(1), table, hand).result!!.shownHands,
        )

        hand.reveal(1, show = true) // A가 SHOW 를 고른다

        // 공개 순서(showdownLeaderSeatNo=1 부터)대로 A, B 둘 다 노출된다.
        assertEquals(
            listOf(
                ShownHandView(seatNo = 1, holeCards = listOf("Kh", "Kd"), category = "PAIR"),
                ShownHandView(seatNo = 2, holeCards = listOf("Ah", "Ad"), category = "PAIR"),
            ),
            publicViewOf(TableId(1), table, hand).result!!.shownHands,
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
    fun `리버 베팅 없이 체크로 끝나면 이긴 좌석만 자동 공개되고 진 좌석이 SHOW 를 고르면 공개 순서(버튼 다음 좌석부터)대로 노출된다`() {
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
        hand.openReveal(Instant.parse("2026-01-01T00:00:10Z"))

        // AA(1)가 이겨 자동 공개 — 진 KK(2)는 아직 선택하지 않아 빠진다.
        assertEquals(
            listOf(ShownHandView(seatNo = 1, holeCards = listOf("Ah", "Ad"), category = "PAIR")),
            publicViewOf(TableId(1), table, hand).result!!.shownHands,
        )

        hand.reveal(2, show = true) // 진 좌석도 SHOW 를 고른다

        // 공개 순서는 버튼(1) 다음 좌석(2)부터 — 순서는 그대로 유지되고 둘 다 노출된다.
        assertEquals(
            listOf(
                ShownHandView(seatNo = 2, holeCards = listOf("Kh", "Kd"), category = "PAIR"),
                ShownHandView(seatNo = 1, holeCards = listOf("Ah", "Ad"), category = "PAIR"),
            ),
            publicViewOf(TableId(1), table, hand).result!!.shownHands,
        )
    }

    @Test
    fun `삼자 쇼다운에서는 전체 승자만 자동 공개되고 나머지는 각자 독립적으로 SHOW·MUCK 을 고른다`() {
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
        hand.openReveal(Instant.parse("2026-01-01T00:00:10Z"))
        assertEquals(setOf(2, 3), hand.awaitingRevealSeatNos) // KK(1)만 이겨서 자동 공개, JJ(2)·QQ(3)는 선택 대상

        assertEquals(
            listOf(ShownHandView(seatNo = 1, holeCards = listOf("Kh", "Kd"), category = "PAIR")),
            publicViewOf(TableId(1), table, hand).result!!.shownHands,
        )

        hand.reveal(3, show = true) // QQ 는 공개
        hand.reveal(2, show = false) // JJ 는 머크
        val view = publicViewOf(TableId(1), table, hand)
        val json = objectMapper.writeValueAsString(view)

        // 공개 순서(버튼 다음인 2부터): 2는 머크해 빠지고 3(QQ)·1(KK) 순서로 공개된다.
        assertEquals(
            listOf(
                ShownHandView(seatNo = 3, holeCards = listOf("Qh", "Qd"), category = "PAIR"),
                ShownHandView(seatNo = 1, holeCards = listOf("Kh", "Kd"), category = "PAIR"),
            ),
            view.result!!.shownHands,
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

    @Test
    fun `공개 뷰는 테이블의 nextHandAt 을 담고 ISO-8601 문자열로 직렬화된다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val scheduledTime = Instant.parse("2026-09-24T12:30:45Z")
        table.scheduleNextHand(scheduledTime)

        val view = publicViewOf(TableId(1), table, null)

        assertEquals(scheduledTime, view.nextHandAt)
        val json = objectMapper.writeValueAsString(view)
        assertTrue(json.contains("\"2026-09-24T12:30:45Z\""), "ISO-8601 형식의 ISO 즉시값이 JSON에 없음: $json")
        assertFalse(json.contains("1695555045"), "Unix 타임스탬프 숫자가 JSON에 포함됨 — Instant 가 숫자로 직렬화됨: $json")
    }

    @Test
    fun `진 좌석이 선택을 기다리는 중 revealDeadline 은 hand 값과 같고, 모두 결정하면 null 이다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
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
        hand.act(2, BettingAction.Check)
        repeat(2) {
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }
        hand.act(2, BettingAction.Check)
        hand.act(1, BettingAction.RaiseTo(Chips.of(200)))
        hand.act(2, BettingAction.Call)

        val revealDeadline = Instant.parse("2026-01-01T00:00:10Z")
        hand.openReveal(revealDeadline)

        // 진 좌석(1)이 선택 대상일 때 revealDeadline 은 hand 값과 같다
        var view = publicViewOf(TableId(1), table, hand)
        assertEquals(1, view.awaitingRevealSeatNos.size)
        assertEquals(revealDeadline, view.revealDeadline)

        // 진 좌석이 선택하면 awaitingRevealSeatNos 가 비고 revealDeadline 도 null 이 된다
        hand.reveal(1, show = true)
        view = publicViewOf(TableId(1), table, hand)
        assertEquals(emptyList(), view.awaitingRevealSeatNos)
        assertNull(view.revealDeadline)
    }
}
