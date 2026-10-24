package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastHandResultTest {

    private fun foldedHand(): Hand {
        val stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000))
        val hand = Hand.start(stacks, buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, Chips.of(100), Chips.of(200), Shuffler { it })
        hand.act(1, BettingAction.Fold)
        hand.act(2, BettingAction.Fold)
        return hand
    }

    @Test
    fun `of 는 행동한 좌석만 사용자와 함께 담고 점유자를 모르는 좌석과 블라인드만 낸 좌석은 뺀다`() {
        val hand = foldedHand()

        val withAll = LastHandResult.of(hand, mapOf(1 to UserId(10L), 2 to UserId(20L), 3 to UserId(30L)))
        val seatTwoStoodUp = LastHandResult.of(hand, mapOf(1 to UserId(10L), 3 to UserId(30L)))

        assertEquals(
            listOf(LastHandResult.LastAction(1, UserId(10L), "FOLD"), LastHandResult.LastAction(2, UserId(20L), "FOLD")),
            withAll.lastActions,
        )
        assertEquals(listOf(LastHandResult.LastAction(1, UserId(10L), "FOLD")), seatTwoStoodUp.lastActions)
    }

    @Test
    fun `lastActionOf 는 좌석과 사용자가 모두 맞을 때만 돌려준다`() {
        val result = LastHandResult.of(foldedHand(), mapOf(1 to UserId(10L), 2 to UserId(20L), 3 to UserId(30L)))

        assertEquals("FOLD", result.lastActionOf(1, UserId(10L)))
        assertNull(result.lastActionOf(1, UserId(99L)))
        assertNull(result.lastActionOf(3, UserId(30L)))
    }
}
