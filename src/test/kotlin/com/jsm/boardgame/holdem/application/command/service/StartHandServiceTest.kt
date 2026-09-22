package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartHandCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
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
        )
}

private class StartHandFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

class StartHandServiceTest {

    private val tables = StartHandFakeTableRepository()
    private val handStore = StartHandFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val handSettler = HandSettler(tables, handStore)
    private val service = StartHandService(tables, handStore, identityShuffler, handSettler)

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
    fun `핸드를 시작하면 버튼이 다음 점유 좌석으로 이동하고 핸드가 저장된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)

        service.start(StartHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(1, savedTable.buttonSeatNo)
        val hand = handStore.find(tableId)
        assertNotNull(hand)
        assertEquals(false, hand.isFinished)
    }

    @Test
    fun `이미 진행 중인 핸드가 있으면 거부한다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val table = tables.findById(tableId)!!
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        handStore.save(tableId, hand)

        val e = assertFailsWith<HandInProgressException> { service.start(StartHandCommand(tableId.value)) }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
    }

    @Test
    fun `양수 스택 좌석이 2명 미만이면 거부한다`() {
        val tableId = tableWithSeats(1 to 10_000L)

        val e = assertFailsWith<IllegalHandStateException> { service.start(StartHandCommand(tableId.value)) }
        assertEquals(HoldemErrorCode.NOT_ENOUGH_PLAYERS, e.errorCode)
    }

    @Test
    fun `0칩 좌석은 핸드에서 제외된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val table = tables.findById(tableId)!!
        table.applyStacks(mapOf(2 to Chips.ZERO))
        tables.save(table)

        service.start(StartHandCommand(tableId.value))

        val hand = handStore.find(tableId)!!
        assertEquals(Chips.of(9_900), hand.stackOf(1))
        assertFailsWith<NoSuchElementException> { hand.stackOf(2) }
        assertEquals(Chips.of(9_800), hand.stackOf(3))
    }

    @Test
    fun `버튼이 0칩 좌석에 놓이면 참가 좌석으로 재배치된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        var table = tables.findById(tableId)!!
        table.applyStacks(mapOf(2 to Chips.ZERO))
        table.moveButtonToNextOccupiedSeat() // null -> 1
        tables.save(table)

        service.start(StartHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(3, savedTable.buttonSeatNo)
        val hand = handStore.find(tableId)!!
        assertEquals(3, hand.buttonSeatNo)
    }

    @Test
    fun `핸드가 시작 직후 끝나면 즉시 정산하고 핸드를 저장하지 않는다`() {
        val tableId = tableWithSeats(1 to 8_000L, 2 to 8_000L)
        val table = tables.findById(tableId)!!
        table.applyStacks(mapOf(1 to Chips.of(100), 2 to Chips.of(100)))
        tables.save(table)

        service.start(StartHandCommand(tableId.value))

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        val total = savedTable.seatAt(1)!!.stack + savedTable.seatAt(2)!!.stack
        assertEquals(Chips.of(200), total)
    }
}
