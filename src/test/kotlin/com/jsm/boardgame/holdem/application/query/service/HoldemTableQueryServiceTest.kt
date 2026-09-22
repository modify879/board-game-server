package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class HoldemTableQueryFakeTableQueryRepository : HoldemTableQueryRepository {
    var location: SeatLocation? = null
    var tables: List<TableSummaryView> = emptyList()

    override fun findSeatOf(userId: Long): SeatLocation? = location
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = PageImpl(tables, pageable, tables.size.toLong())
}

private class HoldemTableQueryFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    fun put(tableId: Long, hand: Hand) { store[tableId] = hand }
    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

class HoldemTableQueryServiceTest {

    private val tableQuery = HoldemTableQueryFakeTableQueryRepository()
    private val handStore = HoldemTableQueryFakeHandStore()
    private val service = HoldemTableQueryService(tableQuery, handStore)

    private fun sampleHand(): Hand =
        Hand.start(mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)), 1, Chips.of(100), Chips.of(200), Shuffler { it })

    @Test
    fun `핸드가 진행 중이 아니면 테이블 목록을 돌려준다`() {
        tableQuery.location = null
        tableQuery.tables = listOf(TableSummaryView(1L, "table-1", 2, 9))

        val page = service.findAll(1L, PageRequest.of(0, 10))

        assertEquals(1, page.totalElements.toInt())
        assertEquals("table-1", page.content[0].name)
    }

    @Test
    fun `핸드가 진행 중이면 로비 조회를 거부한다`() {
        tableQuery.location = SeatLocation(tableId = 5L, seatNo = 1)
        handStore.put(5L, sampleHand())

        val e = assertFailsWith<HandInProgressException> {
            service.findAll(1L, PageRequest.of(0, 10))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
    }
}
