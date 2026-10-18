package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.isInProgress
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ConsistencyFakeTableQueryRepository : HoldemTableQueryRepository {
    val location = SeatLocation(tableId = 5L, seatNo = 1, stack = 1_000L)
    override fun findSeatOf(userId: Long): SeatLocation? = location
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> =
        PageImpl(listOf(TableSummaryView(5L, "t5", 2, 9, false)), pageable, 1)
    override fun findEmptyTableIds(): List<Long> = emptyList()
}

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

    @Test
    fun `끝났지만 정산 전인 핸드에서 방 목록 로비 판단 내 좌석 조회가 같은 답을 낸다`() {
        val store = FakeHandStore()
        store.put(5L, finishedHand())
        val query = ConsistencyFakeTableQueryRepository()

        val lobby = HoldemTableQueryService(query, store).findAll(1L, PageRequest.of(0, 10)) // 거부하지 않는다
        val mySeat = MySeatQueryService(query, store).findMine(1L)!!

        assertFalse(lobby.content.single().handInProgress)
        assertFalse(mySeat.handInProgress)
    }

    @Test
    fun `진행 중인 핸드에서는 셋 모두 진행 중이라고 답한다`() {
        val store = FakeHandStore()
        store.put(5L, newHand())
        val query = ConsistencyFakeTableQueryRepository()

        assertFailsWith<HandInProgressException> { HoldemTableQueryService(query, store).findAll(1L, PageRequest.of(0, 10)) }
        assertEquals(true, MySeatQueryService(query, store).findMine(1L)!!.handInProgress)
    }
}
