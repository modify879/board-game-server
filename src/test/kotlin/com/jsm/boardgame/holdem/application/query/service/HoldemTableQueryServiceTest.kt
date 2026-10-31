package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TablePolicyView
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
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
    val handsInProgress = mutableSetOf<Long>()

    override fun findSeatOf(userId: Long): SeatLocation? = location
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = PageImpl(tables, pageable, tables.size.toLong())
    override fun findEmptyTableIds(): List<Long> = emptyList()
    override fun findPolicyOf(tableId: Long): TablePolicyView? = throw UnsupportedOperationException()
    override fun existsHandInProgress(tableId: Long): Boolean = tableId in handsInProgress
}

class HoldemTableQueryServiceTest {

    private val tableQuery = HoldemTableQueryFakeTableQueryRepository()
    private val service = HoldemTableQueryService(tableQuery)

    @Test
    fun `핸드가 진행 중이 아니면 테이블 목록을 돌려준다`() {
        tableQuery.location = null
        tableQuery.tables = listOf(TableSummaryView(1L, "table-1", 2, 9, false))

        val page = service.findAll(1L, PageRequest.of(0, 10))

        assertEquals(1, page.totalElements.toInt())
        assertEquals("table-1", page.content[0].name)
    }

    @Test
    fun `목록의 handInProgress 는 핸드가 있는 테이블만 true 다`() {
        tableQuery.tables = listOf(TableSummaryView(1L, "t1", 2, 9, false), TableSummaryView(2L, "t2", 0, 9, false))
        tableQuery.handsInProgress += 1L

        val page = service.findAll(1L, PageRequest.of(0, 10))

        assertEquals(listOf(true, false), page.content.map { it.handInProgress })
    }

    @Test
    fun `핸드가 진행 중이면 로비 조회를 거부한다`() {
        tableQuery.location = SeatLocation(tableId = 5L, seatNo = 1, stack = 1_000L)
        tableQuery.handsInProgress += 5L

        val e = assertFailsWith<HandInProgressException> {
            service.findAll(1L, PageRequest.of(0, 10))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
    }
}
