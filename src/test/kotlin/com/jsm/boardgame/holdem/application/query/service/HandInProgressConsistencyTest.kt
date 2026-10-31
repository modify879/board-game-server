package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.isInProgress
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "핸드가 진행 중인가" 의 정의는 하나다 — 끝났지만 정산 전인 핸드는 모든 판단 지점에서 진행 중이 아니다. */
class HandInProgressConsistencyTest {

    private fun newHand(): Hand =
        Hand.start(mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)), 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it })

    private fun finishedHand(): Hand = newHand().also { it.act(it.toActSeatNo!!, BettingAction.Fold) }

    @Test
    fun `isInProgress 는 핸드가 있고 결과가 없을 때만 true 다`() {
        val finished = finishedHand()
        assertTrue(finished.isFinished)

        assertFalse((null as Hand?).isInProgress)
        assertTrue(newHand().isInProgress)
        assertFalse(finished.isInProgress)
    }

    @Test
    fun `findInProgress 는 끝난 핸드를 돌려주지 않고 find 는 돌려준다`() {
        val store = FakeHandStore()
        store.put(5L, finishedHand())

        assertNull(store.findInProgress(TableId(5L)))
        assertTrue(store.find(TableId(5L))!!.isFinished)
        store.put(5L, newHand())
        assertTrue(store.findInProgress(TableId(5L)) != null)
    }
}
