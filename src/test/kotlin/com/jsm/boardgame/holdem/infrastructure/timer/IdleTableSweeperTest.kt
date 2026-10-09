package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableCommand
import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableUseCase
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

private class IdleSweeperMutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

private class IdleSweeperFakeQueries : HoldemTableQueryRepository {
    var emptyIds: List<Long> = emptyList()

    override fun findSeatOf(userId: Long): SeatLocation? = null
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = throw UnsupportedOperationException()
    override fun findEmptyTableIds(): List<Long> = emptyIds
}

private class IdleSweeperFakeCloseUseCase : CloseIdleTableUseCase {
    val closedIds = mutableListOf<Long>()
    val failingIds = mutableSetOf<Long>()
    var result = true

    override fun close(command: CloseIdleTableCommand): Boolean {
        if (command.tableId in failingIds) throw IllegalStateException("닫기 실패")
        closedIds += command.tableId
        return result
    }
}

private class IdleSweeperFakeTableExecutor : TableExecutor {
    override fun <T> call(tableId: TableId, task: () -> T): T = task()
    override fun post(tableId: TableId, task: () -> Unit) = task()
    override fun remove(tableId: TableId) {}
}

class IdleTableSweeperTest {

    private val clock = IdleSweeperMutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val queries = IdleSweeperFakeQueries()
    private val closeUseCase = IdleSweeperFakeCloseUseCase()
    private val sweeper = IdleTableSweeper(SimpleAsyncTaskScheduler(), queries, closeUseCase, IdleSweeperFakeTableExecutor(), clock)

    private fun advance(d: Duration) { clock.now = clock.now.plus(d) }

    @Test
    fun `계속 비어 있어도 1분이 지나기 전에는 닫지 않고 지나면 닫는다`() {
        queries.emptyIds = listOf(1)

        sweeper.sweep()
        advance(Duration.ofSeconds(59))
        sweeper.sweep()
        assertEquals(emptyList(), closeUseCase.closedIds)

        advance(Duration.ofSeconds(1))
        sweeper.sweep()
        assertEquals(listOf(1L), closeUseCase.closedIds)
    }

    @Test
    fun `중간에 후보에서 빠지면 처음부터 다시 센다`() {
        queries.emptyIds = listOf(1)
        sweeper.sweep()
        advance(Duration.ofSeconds(50))

        queries.emptyIds = emptyList() // 누가 앉았다
        sweeper.sweep()
        advance(Duration.ofSeconds(5))
        queries.emptyIds = listOf(1)
        sweeper.sweep()
        advance(Duration.ofSeconds(55))
        sweeper.sweep()

        assertEquals(emptyList(), closeUseCase.closedIds)

        advance(Duration.ofSeconds(5))
        sweeper.sweep()
        assertEquals(listOf(1L), closeUseCase.closedIds)
    }

    @Test
    fun `유스케이스가 닫지 않으면 그 시점부터 다시 센다`() {
        queries.emptyIds = listOf(1)
        sweeper.sweep()
        advance(Duration.ofMinutes(1))
        closeUseCase.result = false
        sweeper.sweep()
        assertEquals(listOf(1L), closeUseCase.closedIds)

        closeUseCase.result = true
        advance(Duration.ofSeconds(30))
        sweeper.sweep()

        assertEquals(listOf(1L), closeUseCase.closedIds)
    }

    @Test
    fun `한 테이블이 실패해도 나머지는 계속 닫는다`() {
        queries.emptyIds = listOf(1, 2)
        closeUseCase.failingIds += 1
        sweeper.sweep()
        advance(Duration.ofMinutes(1))

        sweeper.sweep()

        assertEquals(listOf(2L), closeUseCase.closedIds)
    }
}
