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
import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import java.time.Instant
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
    override fun findTableIdsWithHandInProgress(tableIds: Collection<Long>): Set<Long> = tableIds.filter { it in handsInProgress }.toSet()
}

class HoldemTableQueryServiceTest {

    private val tableQuery = HoldemTableQueryFakeTableQueryRepository()
    private val departures = FakeRecentDepartures()
    private val service = HoldemTableQueryService(tableQuery, departures)

    // 블라인드 50/100, 바이인 20~100BB -> 기본 범위 2_000..10_000
    private fun summary(id: Long, name: String, occupied: Int) =
        TableSummaryView(id, name, occupied, 9, false, 50L, 100L, 20, 100)

    @Test
    fun `의무가 있는 테이블 행만 사용자별 범위와 만료 시각을 싣고 다른 행은 정책 기본 범위다`() {
        val expiresAt = Instant.parse("2026-11-01T00:30:00Z")
        tableQuery.tables = listOf(summary(1L, "t1", 0), summary(2L, "t2", 0), summary(3L, "t3", 0))
        departures.put(UserId(1L), TableId(1L), Departure(Chips.of(3_000), expiresAt))
        departures.put(UserId(1L), TableId(3L), Departure(Chips.of(15_000), expiresAt))
        departures.put(UserId(2L), TableId(2L), Departure(Chips.of(8_000), expiresAt))

        val rows = service.findAll(1L, PageRequest.of(0, 10)).content

        assertEquals(listOf(50L, 50L, 50L), rows.map { it.smallBlind })
        assertEquals(listOf(3_000L, 2_000L, 15_000L), rows.map { it.minBuyIn })
        assertEquals(listOf(10_000L, 10_000L, 15_000L), rows.map { it.maxBuyIn })
        assertEquals(listOf(expiresAt, null, expiresAt), rows.map { it.obligationExpiresAt })
    }

    @Test
    fun `핸드가 진행 중이 아니면 테이블 목록을 돌려준다`() {
        tableQuery.location = null
        tableQuery.tables = listOf(summary(1L, "table-1", 2))

        val page = service.findAll(1L, PageRequest.of(0, 10))

        assertEquals(1, page.totalElements.toInt())
        assertEquals("table-1", page.content[0].name)
    }

    @Test
    fun `목록의 handInProgress 는 핸드가 있는 테이블만 true 다`() {
        tableQuery.tables = listOf(summary(1L, "t1", 2), summary(2L, "t2", 0))
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
