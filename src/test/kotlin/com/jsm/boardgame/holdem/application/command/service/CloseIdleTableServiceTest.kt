package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableCommand
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CloseIdleFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    // 실제 어댑터처럼 닫힌 테이블은 없는 테이블로 돌려준다.
    override fun findById(id: TableId): HoldemTable? = stored[id.value]?.takeIf { it.closedAt == null }

    override fun findByUserId(userId: UserId): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findAllSeatedUserIds(): List<UserId> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllTableIds(): List<TableId> = stored.values.filter { it.closedAt == null }.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            closedAt = table.closedAt,
        )
        stored[id.value] = saved
        return saved
    }
}

private class CloseIdleFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

class CloseIdleTableServiceTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val tables = CloseIdleFakeHoldemTableRepository()
    private val handStore = CloseIdleFakeHandStore()
    private val joinQueue = InMemoryJoinQueue()
    private val events = mutableListOf<Any>()
    private val service = CloseIdleTableService(
        tables, handStore, joinQueue, ApplicationEventPublisher { events += it }, Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun emptyTable(): TableId = tables.save(HoldemTable.create("빈 테이블")).id!!

    @Test
    fun `좌석·핸드·대기열이 모두 비어 있으면 닫고 TableClosed 를 올린다`() {
        val id = emptyTable()

        assertTrue(service.close(CloseIdleTableCommand(id.value)))

        assertEquals(now, tables.stored[id.value]!!.closedAt)
        assertNull(tables.findById(id))
        assertEquals(listOf<Any>(TableClosed(id)), events)
    }

    @Test
    fun `앉은 사용자가 있으면 아무것도 하지 않는다`() {
        val id = emptyTable()
        tables.save(tables.findById(id)!!.also { it.sitDown(UserId(1L), Chips.of(8_000)) })

        assertFalse(service.close(CloseIdleTableCommand(id.value)))

        assertNotNull(tables.findById(id))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `핸드가 진행 중이면 아무것도 하지 않는다`() {
        val id = emptyTable()
        handStore.save(
            id,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it }),
        )

        assertFalse(service.close(CloseIdleTableCommand(id.value)))

        assertNotNull(tables.findById(id))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `대기열이 비어 있지 않으면 아무것도 하지 않는다`() {
        val id = emptyTable()
        joinQueue.enqueue(id, userId = UserId(1L), postBlindImmediately = false)

        assertFalse(service.close(CloseIdleTableCommand(id.value)))

        assertNotNull(tables.findById(id))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `테이블이 없으면 아무것도 하지 않는다`() {
        assertFalse(service.close(CloseIdleTableCommand(999)))
        assertTrue(events.isEmpty())
    }
}
