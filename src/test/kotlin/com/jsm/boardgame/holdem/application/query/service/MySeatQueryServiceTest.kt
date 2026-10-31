package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TablePolicyView
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class MySeatQueryFakeTableQueryRepository : HoldemTableQueryRepository {
    var location: SeatLocation? = null
    val handsInProgress = mutableSetOf<Long>()

    override fun findSeatOf(userId: Long): SeatLocation? = location
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = throw UnsupportedOperationException()
    override fun findEmptyTableIds(): List<Long> = emptyList()
    override fun findPolicyOf(tableId: Long): TablePolicyView? = throw UnsupportedOperationException()
    override fun existsHandInProgress(tableId: Long): Boolean = tableId in handsInProgress
}

class MySeatQueryServiceTest {

    private val tableQuery = MySeatQueryFakeTableQueryRepository()
    private val service = MySeatQueryService(tableQuery)

    @Test
    fun `미착석이면 null 을 돌려준다`() {
        tableQuery.location = null

        assertNull(service.findMine(1L))
    }

    @Test
    fun `핸드가 진행 중이지 않으면 handInProgress 가 false 다`() {
        tableQuery.location = SeatLocation(tableId = 5L, seatNo = 2, stack = 1_500L)

        val view = service.findMine(1L)!!
        assertEquals(5L, view.tableId)
        assertEquals(2, view.seatNo)
        assertEquals(false, view.handInProgress)
        assertEquals(1_500L, view.stack)
    }

    @Test
    fun `핸드가 진행 중이면 handInProgress 가 true 다`() {
        tableQuery.location = SeatLocation(tableId = 5L, seatNo = 2, stack = 1_500L)
        tableQuery.handsInProgress += 5L

        val view = service.findMine(1L)!!
        assertEquals(true, view.handInProgress)
    }
}
