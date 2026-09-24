package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartHandCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class StartHandFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]?.let { copyOf(it) }

    override fun findByUserId(userId: Long): HoldemTable? =
        store.values.firstOrNull { it.seatOf(userId) != null }?.let { copyOf(it) }

    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: TableId(nextId++)
        val saved = copyOf(table, id)
        store[id.value] = saved
        return saved
    }

    private fun copyOf(table: HoldemTable, id: TableId = table.id!!): HoldemTable =
        HoldemTable.reconstitute(
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
}

private class StartHandFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

class StartHandServiceTest {

    private val tables = StartHandFakeTableRepository()
    private val handStore = StartHandFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val handSettler = HandSettler(tables, handStore, eventPublisher, clock)
    private val handStarter = HandStarter(tables, handStore, identityShuffler, handSettler, eventPublisher)
    private val service = StartHandService(tables, handStore, handStarter)

    private fun start(tableId: TableId, userId: Long = 1L) {
        service.start(StartHandCommand(tableId.value, userId))
    }

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
    fun `핸드를 시작하면 첫 핸드는 가장 작은 참가 좌석이 BB 가 되고 핸드가 저장된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)

        start(tableId)

        val savedTable = tables.findById(tableId)!!
        assertEquals(2, savedTable.buttonSeatNo)
        assertEquals(3, savedTable.smallBlindSeatNo)
        assertEquals(1, savedTable.bigBlindSeatNo)
        val hand = handStore.find(tableId)
        assertNotNull(hand)
        assertEquals(false, hand.isFinished)
    }

    @Test
    fun `테이블이 없으면 TABLE_NOT_FOUND 로 거부된다`() {
        val e = assertFailsWith<TableNotFoundException> { start(TableId(999L)) }
        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `이미 진행 중인 핸드가 있으면 거부한다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val table = tables.findById(tableId)!!
        table.moveButtonToNextOccupiedSeat()
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

        val e = assertFailsWith<HandInProgressException> { start(tableId) }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
    }

    @Test
    fun `다음 핸드가 자동으로 시작될 예정이면 수동 시작은 거부된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val table = tables.findById(tableId)!!
        table.scheduleNextHand(Instant.now(clock).plusSeconds(5))
        tables.save(table)

        val e = assertFailsWith<HandInProgressException> { start(tableId) }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
        assertNull(handStore.find(tableId))
    }

    @Test
    fun `앉지 않은 사용자가 핸드를 시작하면 NOT_SEATED 로 거부되고 핸드가 생기지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        val e = assertFailsWith<NotSeatedException> { start(tableId, userId = 999L) }

        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
        assertNull(handStore.find(tableId))
    }
}
