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
import org.springframework.context.ApplicationEventPublisher
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
    private val handSettler = HandSettler(tables, handStore, eventPublisher)
    private val service = StartHandService(tables, handStore, identityShuffler, handSettler, eventPublisher)

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

        service.start(StartHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(2, savedTable.buttonSeatNo)
        assertEquals(3, savedTable.smallBlindSeatNo)
        assertEquals(1, savedTable.bigBlindSeatNo)
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
            buttonSeatNo = table.buttonSeatNo!!,
            smallBlindSeatNo = table.buttonSeatNo!!,
            bigBlindSeatNo = 2,
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
    fun `직전 SB 좌석이 빈 채로 다음 핸드를 시작하면 버튼이 그 좌석 번호에 그대로 남는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L, 4 to 10_000L)

        service.start(StartHandCommand(tableId.value)) // 1핸드(첫 핸드): BB=1, SB=4, 버튼=3
        handStore.remove(tableId) // 정산 없이 다음 핸드로 넘어가는 상황을 흉내낸다

        var table = tables.findById(tableId)!!
        table.applyStacks(mapOf(4 to Chips.ZERO)) // 직전 SB(4) 좌석이 이번 핸드엔 없다
        tables.save(table)

        service.start(StartHandCommand(tableId.value)) // 2핸드: 직전 SB(4)가 다음 버튼이 되는데, 비어 있어도 그대로(dead button)

        val savedTable = tables.findById(tableId)!!
        assertEquals(4, savedTable.buttonSeatNo)
        val hand = handStore.find(tableId)!!
        assertEquals(4, hand.buttonSeatNo)
        assertFailsWith<NoSuchElementException> { hand.stackOf(4) } // 4번은 이번 핸드에 없다
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
