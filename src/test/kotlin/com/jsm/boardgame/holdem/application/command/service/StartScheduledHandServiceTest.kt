package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.OpenShowdown
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StartScheduledHandFakeTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]
    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllPendingNextHandTableIds(): List<TableId> =
        stored.values.filter { it.nextHandAt != null }.mapNotNull { it.id }
    override fun findAllTableIdsWithPendingJoinRequests(): List<TableId> = emptyList()

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            version = table.version,
            smallBlindSeatNo = table.smallBlindSeatNo,
            bigBlindSeatNo = table.bigBlindSeatNo,
            nextHandAt = table.nextHandAt,
        )
        stored[id.value] = saved
        return saved
    }
}

private class StartScheduledHandFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class StartScheduledHandFakeShowdownStore : ShowdownStore {
    private val store = mutableMapOf<Long, OpenShowdown>()
    override fun find(tableId: TableId): OpenShowdown? = store[tableId.value]
    override fun save(tableId: TableId, showdown: OpenShowdown) { store[tableId.value] = showdown }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

class StartScheduledHandServiceTest {

    private val tables = StartScheduledHandFakeTableRepository()
    private val handStore = StartScheduledHandFakeHandStore()
    private val showdownStore = StartScheduledHandFakeShowdownStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)
    private val revealTimeout: Duration = Duration.ofSeconds(10)
    private val handSettler = HandSettler(tables, handStore, showdownStore, eventPublisher, clock, nextHandDelay, revealTimeout)
    private val handStarter = HandStarter(tables, handStore, showdownStore, identityShuffler, handSettler, eventPublisher, clock, nextHandDelay)
    private val service = StartScheduledHandService(tables, handStore, handStarter, clock)

    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in buyIns) {
            table.sitDown(seatNo, userId = seatNo.toLong(), buyIn = Chips.of(buyIn))
        }
        table = tables.save(table)
        return table.id!!
    }

    @Test
    fun `테이블이 없으면 아무 일도 하지 않는다`() {
        service.start(StartScheduledHandCommand(999L))
        // 예외 없이 반환되면 충분하다.
    }

    @Test
    fun `이미 진행 중인 핸드가 있으면 아무 일도 하지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val table = tables.findById(tableId)!!
        table.moveButtonToNextOccupiedSeat()
        table.scheduleNextHand(Instant.now(clock).plus(Duration.ofSeconds(5)))
        tables.save(table)
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            buttonSeatNo = table.buttonSeatNo!!,
            smallBlindSeatNo = table.buttonSeatNo!!,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        handStore.save(tableId, hand)

        service.start(StartScheduledHandCommand(tableId.value))

        assertEquals(hand, handStore.find(tableId))
        assertTrue(tables.findById(tableId)!!.nextHandAt != null)
    }

    @Test
    fun `nextHandAt 이 비어 있으면 아무 일도 하지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        service.start(StartScheduledHandCommand(tableId.value))

        assertNull(handStore.find(tableId))
        assertNull(tables.findById(tableId)!!.nextHandAt)
    }

    @Test
    fun `nextHandAt 이 설정돼 있고 후보가 2명 이상이면 핸드가 시작되고 nextHandAt 이 해제된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val table = tables.findById(tableId)!!
        table.scheduleNextHand(Instant.now(clock).minusSeconds(1)) // 이미 도래한 시각 — 고정 시계에서도 항상 시작 조건을 만족한다
        tables.save(table)

        service.start(StartScheduledHandCommand(tableId.value))

        val hand = handStore.find(tableId)
        assertTrue(hand != null)
        assertNull(tables.findById(tableId)!!.nextHandAt)
    }

    @Test
    fun `nextHandAt 이 설정돼 있지만 후보가 2명 미만이면 핸드는 생성되지 않고 nextHandAt 이 해제되며 예외가 나지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L)
        val table = tables.findById(tableId)!!
        table.scheduleNextHand(Instant.now(clock).minusSeconds(1)) // 이미 도래한 시각
        tables.save(table)

        service.start(StartScheduledHandCommand(tableId.value)) // 예외 없이 끝나야 한다

        assertNull(handStore.find(tableId))
        assertNull(tables.findById(tableId)!!.nextHandAt)
    }

    @Test
    fun `nextHandAt 이 아직 도래하지 않았으면 조용히 반환하고 아무 것도 바뀌지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val table = tables.findById(tableId)!!
        val future = Instant.now(clock).plus(Duration.ofSeconds(5))
        table.scheduleNextHand(future)
        tables.save(table)

        service.start(StartScheduledHandCommand(tableId.value))

        assertNull(handStore.find(tableId))
        assertEquals(future, tables.findById(tableId)!!.nextHandAt)
    }
}
