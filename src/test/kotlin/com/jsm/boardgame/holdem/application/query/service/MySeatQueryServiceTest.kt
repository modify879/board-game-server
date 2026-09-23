package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class MySeatQueryFakeTableQueryRepository : HoldemTableQueryRepository {
    var location: SeatLocation? = null

    override fun findSeatOf(userId: Long): SeatLocation? = location
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = throw UnsupportedOperationException()
}

private class MySeatQueryFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    fun put(tableId: Long, hand: Hand) { store[tableId] = hand }
    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

class MySeatQueryServiceTest {

    private val tableQuery = MySeatQueryFakeTableQueryRepository()
    private val handStore = MySeatQueryFakeHandStore()
    private val service = MySeatQueryService(tableQuery, handStore)

    private fun sampleHand(): Hand =
        Hand.start(mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)), 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it })

    @Test
    fun `미착석이면 null 을 돌려준다`() {
        tableQuery.location = null

        assertNull(service.findMine(1L))
    }

    @Test
    fun `핸드가 진행 중이지 않으면 handInProgress 가 false 다`() {
        tableQuery.location = SeatLocation(tableId = 5L, seatNo = 2)

        val view = service.findMine(1L)!!
        assertEquals(5L, view.tableId)
        assertEquals(2, view.seatNo)
        assertEquals(false, view.handInProgress)
    }

    @Test
    fun `핸드가 진행 중이면 handInProgress 가 true 다`() {
        tableQuery.location = SeatLocation(tableId = 5L, seatNo = 2)
        handStore.put(5L, sampleHand())

        val view = service.findMine(1L)!!
        assertEquals(true, view.handInProgress)
    }
}
