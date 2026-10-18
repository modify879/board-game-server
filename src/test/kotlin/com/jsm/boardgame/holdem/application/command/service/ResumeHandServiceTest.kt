package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.placeButtonAtFirstSeat
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class ResumeHandFakeTableRepository : HoldemTableRepository {
    val store = mutableMapOf<Long, HoldemTable>()
    var saveCalls = 0

    override fun findById(id: TableId): HoldemTable? = store[id.value]
    override fun findByUserId(userId: UserId): HoldemTable? = store.values.firstOrNull { it.seatOf(userId) != null }
    override fun findAllSeatedUserIds(): List<UserId> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = store.values.mapNotNull { it.id }
    override fun save(table: HoldemTable): HoldemTable {
        saveCalls++
        store[table.id!!.value] = table
        return table
    }
}

private class ResumeHandFakeHandStore : HandStore {
    val store = mutableMapOf<Long, Hand>()
    var saveCalls = 0

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { saveCalls++; store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

class ResumeHandServiceTest {

    private val tables = ResumeHandFakeTableRepository()
    private val handStore = ResumeHandFakeHandStore()
    private val events = mutableListOf<Any>()
    private val service = ResumeHandService(tables, handStore, ApplicationEventPublisher { events += it })
    private val tableId = TableId(1L)

    private fun storeHand(): Hand {
        val table = HoldemTable.reconstitute(
            id = tableId, name = "t", policy = TestTablePolicy,
            buttonSeatNo = null, seats = emptyMap(),
        )
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))
        table.sitDown(userId = UserId(2L), buyIn = Chips.of(10_000))
        table.placeButtonAtFirstSeat()
        tables.store[tableId.value] = table
        val stacks = table.occupiedSeats().associate { it.seatNo to Chips.of(10_000) }
        val button = table.buttonSeatNo!!
        val hand = Hand.start(
            stacks, button, button, stacks.keys.single { it != button },
            table.smallBlind, table.bigBlind, Shuffler { it },
        )
        handStore.store[tableId.value] = hand
        return hand
    }

    @Test
    fun `저장된 진행 중 핸드가 있으면 그 테이블과 핸드를 실어 브로드캐스트를 요청한다`() {
        val hand = storeHand()

        service.resume(ResumeHandCommand(tableId.value))

        val event = events.single() as HandBroadcastRequested
        assertEquals(tableId, event.tableId)
        assertSame(hand, event.hand)
        assertSame(tables.store[tableId.value], event.table)
        assertEquals(null, event.lastResult)
    }

    @Test
    fun `재개는 상태를 바꾸지 않는다 - 저장도 하지 않고 핸드와 좌석 스택이 그대로다`() {
        val hand = storeHand()
        val stacksBefore = tables.store[tableId.value]!!.occupiedSeats().associate { it.seatNo to it.stack }
        val toActBefore = hand.toActSeatNo

        service.resume(ResumeHandCommand(tableId.value))

        assertEquals(0, tables.saveCalls)
        assertEquals(0, handStore.saveCalls)
        assertSame(hand, handStore.find(tableId))
        assertEquals(toActBefore, hand.toActSeatNo)
        assertEquals(stacksBefore, tables.store[tableId.value]!!.occupiedSeats().associate { it.seatNo to it.stack })
    }

    @Test
    fun `런아웃 중인 핸드도 그대로 실어 보낸다`() {
        val hand = storeHand()
        hand.act(hand.toActSeatNo!!, BettingAction.RaiseTo(Chips.of(10_000)))
        hand.act(hand.toActSeatNo!!, BettingAction.Call)
        assertTrue(hand.isRunningOut)

        service.resume(ResumeHandCommand(tableId.value))

        val event = events.single() as HandBroadcastRequested
        assertSame(hand, event.hand)
        assertTrue(event.hand!!.isRunningOut)
    }

    @Test
    fun `저장된 핸드가 없으면 아무 것도 발행하지 않고 조용히 끝난다`() {
        storeHand()
        handStore.remove(tableId)

        service.resume(ResumeHandCommand(tableId.value))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `테이블이 없으면 아무 것도 발행하지 않고 조용히 끝난다`() {
        storeHand()
        tables.store.clear()

        service.resume(ResumeHandCommand(tableId.value))

        assertTrue(events.isEmpty())
    }
}
