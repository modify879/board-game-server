package com.jsm.boardgame.holdem.presentation.ws.payload

import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val identityShuffler = Shuffler { it }

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
        assertEquals(2, view.seats.size)
        assertTrue(view.seats.all { it.status == "SITTING_OUT" })
        assertEquals(10_000L, view.seats.first { it.seatNo == 1 }.stack)
    }

    @Test
    fun `좌석 A 의 개인 뷰에는 좌석 B 의 카드가 들어가지 않는다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )

        val viewA = privateViewOf(TableId(1), 1, hand)
        val viewB = privateViewOf(TableId(1), 2, hand)

        assertEquals(listOf("2s", "4s"), viewA.holeCards)
        assertEquals(listOf("3s", "5s"), viewB.holeCards)
        assertTrue(viewA.holeCards.none { it in viewB.holeCards })
    }

    @Test
    fun `폴드한 좌석은 공개 뷰에서 FOLDED 로 표시되고 핸드는 계속 진행 중이다`() {
        val table = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        table.moveButtonToNextOccupiedSeat() // null -> 1
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        val toAct = hand.toActSeatNo!!

        hand.act(toAct, BettingAction.Fold)

        val view = publicViewOf(TableId(1), table, hand)
        assertFalse(hand.isFinished)
        assertTrue(view.handInProgress)
        assertEquals("FOLDED", view.seats.first { it.seatNo == toAct }.status)
        assertEquals(emptyList(), view.board) // 아직 프리플랍
    }

    @Test
    fun `블라인드만으로 양쪽이 올인되면 공개 뷰에 ALL_IN 과 최종 보드가 담긴다`() {
        val table = tableWithSeats(1 to 8_000L, 2 to 8_000L)
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(100), 2 to Chips.of(100)),
            table.buttonSeatNo!!,
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
}
