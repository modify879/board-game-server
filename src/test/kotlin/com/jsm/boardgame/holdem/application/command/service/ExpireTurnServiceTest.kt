package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatStatus
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class ExpireTurnFakeTableRepository : HoldemTableRepository {
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

private class ExpireTurnFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

class ExpireTurnServiceTest {

    private val tables = ExpireTurnFakeTableRepository()
    private val handStore = ExpireTurnFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val handSettler = HandSettler(tables, handStore, eventPublisher)
    private val service = ExpireTurnService(tables, handStore, handSettler, eventPublisher)

    /** userId = seatNo * 1000 으로 대응시킨다. */
    private fun tableWithHand(vararg stacks: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in stacks) {
            table.sitDown(seatNo, userId = seatNo * 1000L, buyIn = Chips.of(buyIn))
        }
        table.moveButtonToNextOccupiedSeat()
        table = tables.save(table)

        val handStacks = stacks.associate { (seatNo, buyIn) -> seatNo to Chips.of(buyIn) }
        val hand = Hand.start(handStacks, table.buttonSeatNo!!, table.smallBlind, table.bigBlind, identityShuffler)
        handStore.save(table.id!!, hand)
        return table.id!!
    }

    @Test
    fun `차례인 좌석이 만료되면 폴드되고 핸드는 계속 진행된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        service.expire(ExpireTurnCommand(tableId.value, toAct))

        val hand = handStore.find(tableId)
        assertNotNull(hand)
        assertFalse(hand.isFinished)
        assertEquals(SeatStatus.FOLDED, hand.statusOf(toAct))
    }

    @Test
    fun `차례가 아닌 좌석의 만료 요청은 아무 일도 하지 않는다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!
        val notToAct = (setOf(1, 2, 3) - toAct).first()

        service.expire(ExpireTurnCommand(tableId.value, notToAct))

        val hand = handStore.find(tableId)
        assertNotNull(hand)
        assertEquals(toAct, hand.toActSeatNo)
        assertEquals(SeatStatus.ACTIVE, hand.statusOf(notToAct))
    }

    @Test
    fun `핸드가 없으면 조용히 끝난다`() {
        var table = HoldemTable.create("no-hand")
        table = tables.save(table)
        table.sitDown(1, userId = 1000L, buyIn = Chips.of(10_000))
        tables.save(table)

        service.expire(ExpireTurnCommand(table.id!!.value, 1))

        assertNull(handStore.find(table.id!!))
    }

    @Test
    fun `테이블이 없으면 조용히 끝난다`() {
        service.expire(ExpireTurnCommand(999, 1))
        // 예외 없이 반환되면 충분하다.
    }

    @Test
    fun `만료 폴드로 핸드가 끝나면 정산된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        service.expire(ExpireTurnCommand(tableId.value, toAct))

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        val total = savedTable.seatAt(1)!!.stack + savedTable.seatAt(2)!!.stack
        assertEquals(Chips.of(20_000), total)
    }
}
