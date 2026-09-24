package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class CancelHandFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]?.let { copyOf(it) }

    override fun findByUserId(userId: Long): HoldemTable? =
        store.values.firstOrNull { it.seatOf(userId) != null }?.let { copyOf(it) }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null

    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }

    override fun findAllTableIdsWithPendingJoinRequests(): List<TableId> = emptyList()

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
            nextHandAt = table.nextHandAt,
        )
}

private class CancelHandFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

class CancelHandServiceTest {

    private val tables = CancelHandFakeTableRepository()
    private val handStore = CancelHandFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val service = CancelHandService(tables, handStore, eventPublisher)

    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in buyIns) {
            table.sitDown(seatNo, userId = seatNo.toLong(), buyIn = Chips.of(buyIn))
        }
        table = tables.save(table)
        return table.id!!
    }

    private fun startAndStoreHand(tableId: TableId, startingStacks: Map<Int, Chips>): Hand {
        val table = tables.findById(tableId)!!
        table.moveButtonToNextOccupiedSeat()
        val buttonSeatNo = table.buttonSeatNo!!
        val seatNos = startingStacks.keys.sorted()
        fun nextSeatNo(from: Int): Int = seatNos[(seatNos.indexOf(from) + 1) % seatNos.size]
        val (smallBlindSeatNo, bigBlindSeatNo) = if (seatNos.size == 2) {
            buttonSeatNo to nextSeatNo(buttonSeatNo)
        } else {
            val sb = nextSeatNo(buttonSeatNo)
            sb to nextSeatNo(sb)
        }
        val hand = Hand.start(startingStacks, buttonSeatNo, smallBlindSeatNo, bigBlindSeatNo, table.smallBlind, table.bigBlind, identityShuffler)
        handStore.save(tableId, hand)
        return hand
    }

    @Test
    fun `취소하면 좌석 스택이 시작 스택으로 정확히 되돌아간다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val startingStacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000))
        val hand = startAndStoreHand(tableId, startingStacks)
        // 스택을 흔들어 놓는다 - 취소가 hand.stackOf() 가 아니라 startingStacks 로 되돌리는지 검증하려면
        // 취소 시점에 스택이 이미 달라져 있어야 한다.
        hand.act(hand.toActSeatNo!!, BettingAction.Call)

        service.cancel(CancelHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(Chips.of(10_000), savedTable.seatAt(1)!!.stack)
        assertEquals(Chips.of(10_000), savedTable.seatAt(2)!!.stack)
        assertEquals(Chips.of(10_000), savedTable.seatAt(3)!!.stack)
    }

    @Test
    fun `취소하면 진행 중 핸드 행이 삭제된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)))

        service.cancel(CancelHandCommand(tableId.value))

        assertNull(handStore.find(tableId))
    }

    @Test
    fun `취소해도 좌석은 유지된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)))

        service.cancel(CancelHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(2, savedTable.occupiedSeats().size)
    }

    @Test
    fun `진행 중 핸드가 없으면 조용히 끝난다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        service.cancel(CancelHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(Chips.of(10_000), savedTable.seatAt(1)!!.stack)
        assertEquals(Chips.of(10_000), savedTable.seatAt(2)!!.stack)
        assertEquals(2, savedTable.occupiedSeats().size)
    }

    @Test
    fun `취소 전후 스택 총합이 보존된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val totalBefore = tables.findById(tableId)!!.occupiedSeats().sumOf { it.stack.amount }
        val hand = startAndStoreHand(
            tableId,
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
        )
        hand.act(hand.toActSeatNo!!, BettingAction.Call)

        service.cancel(CancelHandCommand(tableId.value))

        val totalAfter = tables.findById(tableId)!!.occupiedSeats().sumOf { it.stack.amount }
        assertEquals(totalBefore, totalAfter)
    }
}
