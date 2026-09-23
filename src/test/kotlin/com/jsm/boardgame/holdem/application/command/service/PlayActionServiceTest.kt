package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.PlayActionCommand
import com.jsm.boardgame.holdem.application.exception.HandNotFoundException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.exception.UnknownActionException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalBettingActionException
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
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
import kotlin.test.assertNull

private class PlayActionFakeTableRepository : HoldemTableRepository {
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
        )
}

private class PlayActionFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

class PlayActionServiceTest {

    private val tables = PlayActionFakeTableRepository()
    private val handStore = PlayActionFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val handSettler = HandSettler(tables, handStore, eventPublisher)
    private val service = PlayActionService(tables, handStore, handSettler, eventPublisher)

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
        val buttonSeatNo = table.buttonSeatNo!!
        val seatNos = handStacks.keys.sorted()
        fun nextSeatNo(from: Int): Int = seatNos[(seatNos.indexOf(from) + 1) % seatNos.size]
        val (smallBlindSeatNo, bigBlindSeatNo) = if (seatNos.size == 2) {
            buttonSeatNo to nextSeatNo(buttonSeatNo)
        } else {
            val sb = nextSeatNo(buttonSeatNo)
            sb to nextSeatNo(sb)
        }
        val hand = Hand.start(handStacks, buttonSeatNo, smallBlindSeatNo, bigBlindSeatNo, table.smallBlind, table.bigBlind, identityShuffler)
        handStore.save(table.id!!, hand)
        return table.id!!
    }

    @Test
    fun `정상 액션이 진행되면 핸드 상태가 갱신된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        service.play(PlayActionCommand(tableId.value, userId = toAct * 1000L, action = "CALL", raiseToAmount = null))

        val hand = handStore.find(tableId)!!
        assertEquals(false, hand.isFinished)
        assertEquals(if (toAct == 1) 2 else 1, hand.toActSeatNo)
    }

    @Test
    fun `테이블이 없으면 거부한다`() {
        val e = assertFailsWith<TableNotFoundException> {
            service.play(PlayActionCommand(999, userId = 1000L, action = "FOLD", raiseToAmount = null))
        }
        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `진행 중인 핸드가 없으면 거부한다`() {
        var table = HoldemTable.create("no-hand")
        table = tables.save(table)
        table.sitDown(1, userId = 1000L, buyIn = Chips.of(10_000))
        tables.save(table)

        val e = assertFailsWith<HandNotFoundException> {
            service.play(PlayActionCommand(table.id!!.value, userId = 1000L, action = "FOLD", raiseToAmount = null))
        }
        assertEquals(HoldemErrorCode.HAND_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `앉아 있지 않은 사용자의 액션은 거부한다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)

        val e = assertFailsWith<NotSeatedException> {
            service.play(PlayActionCommand(tableId.value, userId = 9999L, action = "FOLD", raiseToAmount = null))
        }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    @Test
    fun `차례가 아닌 사용자의 액션은 도메인 에러 코드로 거부된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!
        val notToAct = if (toAct == 1) 2 else 1

        val e = assertFailsWith<IllegalBettingActionException> {
            service.play(PlayActionCommand(tableId.value, userId = notToAct * 1000L, action = "CALL", raiseToAmount = null))
        }
        assertEquals(HoldemErrorCode.NOT_YOUR_TURN, e.errorCode)
    }

    @Test
    fun `알 수 없는 액션 문자열은 거부한다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        val e = assertFailsWith<UnknownActionException> {
            service.play(PlayActionCommand(tableId.value, userId = toAct * 1000L, action = "RAISE", raiseToAmount = null))
        }
        assertEquals(HoldemErrorCode.UNKNOWN_ACTION, e.errorCode)
    }

    @Test
    fun `RAISE_TO 인데 금액이 없으면 거부한다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        val e = assertFailsWith<UnknownActionException> {
            service.play(PlayActionCommand(tableId.value, userId = toAct * 1000L, action = "RAISE_TO", raiseToAmount = null))
        }
        assertEquals(HoldemErrorCode.UNKNOWN_ACTION, e.errorCode)
    }

    @Test
    fun `핸드가 액션으로 종료되면 좌석 스택이 갱신되고 handStore 에서 제거된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        service.play(PlayActionCommand(tableId.value, userId = toAct * 1000L, action = "FOLD", raiseToAmount = null))

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        val total = savedTable.seatAt(1)!!.stack + savedTable.seatAt(2)!!.stack
        assertEquals(Chips.of(20_000), total)
    }

    @Test
    fun `핸드 도중 새로 앉은 좌석이 있어도 정산이 터지지 않고 새 좌석 스택은 바이인 그대로다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        val table = tables.findById(tableId)!!
        table.sitDown(3, userId = 3000L, buyIn = Chips.of(10_000))
        tables.save(table)

        service.play(PlayActionCommand(tableId.value, userId = toAct * 1000L, action = "FOLD", raiseToAmount = null))

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        val total = savedTable.seatAt(1)!!.stack + savedTable.seatAt(2)!!.stack
        assertEquals(Chips.of(20_000), total)
        assertEquals(Chips.of(10_000), savedTable.seatAt(3)!!.stack)
    }
}
