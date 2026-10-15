package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.FakeFoldWinnerCards
import com.jsm.boardgame.holdem.application.port.FakeHandHistory
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.FakeWalletTransfer
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AdvanceRunoutFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    override fun findById(id: TableId): HoldemTable? = store[id.value]
    override fun findByUserId(userId: UserId): HoldemTable? = store.values.find { it.seatOf(userId) != null }
    override fun findAllSeatedUserIds(): List<UserId> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = store.values.mapNotNull { it.id }
    override fun save(table: HoldemTable): HoldemTable {
        store[table.id!!.value] = table
        return table
    }
}

private class AdvanceRunoutFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()
    var saves = 0
    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { saves++; store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

private class AdvanceRunoutFakeLastHandResultStore : LastHandResultStore {
    val store = mutableMapOf<Long, LastHandResult>()
    override fun find(tableId: TableId): LastHandResult? = store[tableId.value]
    override fun save(tableId: TableId, result: LastHandResult) { store[tableId.value] = result }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

private class AdvanceRunoutFakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}

private class AdvanceRunoutFakeNextHandCountdown : NextHandCountdown {
    override fun restart(tableId: TableId) {}
    override fun cancel(tableId: TableId) {}
    override fun remaining(tableId: TableId): Duration? = null
}

class AdvanceRunoutServiceTest {

    private val tables = AdvanceRunoutFakeTableRepository()
    private val handStore = AdvanceRunoutFakeHandStore()
    private val lastHandResults = AdvanceRunoutFakeLastHandResultStore()
    private val eventPublisher = AdvanceRunoutFakeEventPublisher()
    private val handSettler = HandSettler(tables, handStore, lastHandResults, FakeFoldWinnerCards(), eventPublisher, AdvanceRunoutFakeNextHandCountdown(), FakeRecentDepartures(), FakeHandHistory(), LeaveReservations(FakeRecentDepartures(), FakeWalletTransfer()))
    private val service = AdvanceRunoutService(tables, handStore, handSettler, eventPublisher)

    private fun tableWithHand(allIn: Boolean): TableId {
        val table = HoldemTable.reconstitute(TableId(1), "t", Chips.of(100), Chips.of(200), 1, emptyMap())
        table.sitDown(UserId(1000L), Chips.of(10_000))
        table.sitDown(UserId(2000L), Chips.of(10_000))
        tables.save(table)
        val stacks = mapOf(1 to Chips.of(1_000), 2 to Chips.of(1_000))
        val hand = Hand.start(stacks, 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it })
        if (allIn) {
            hand.act(1, BettingAction.RaiseTo(Chips.of(1_000)))
            hand.act(2, BettingAction.Call)
        }
        handStore.save(table.id!!, hand)
        handStore.saves = 0
        return table.id
    }

    @Test
    fun `테이블이나 핸드가 없으면 아무것도 하지 않는다`() {
        service.advance(AdvanceRunoutCommand(99))

        assertTrue(eventPublisher.events.isEmpty())
    }

    @Test
    fun `런아웃 중이 아니면 낡은 발화로 보고 무시한다`() {
        val tableId = tableWithHand(allIn = false)

        service.advance(AdvanceRunoutCommand(tableId.value))

        assertEquals(0, handStore.saves)
        assertTrue(eventPublisher.events.isEmpty())
        assertEquals(0, handStore.find(tableId)!!.board.size)
    }

    @Test
    fun `한 걸음 진행하면 저장하고 브로드캐스트를 요청한다`() {
        val tableId = tableWithHand(allIn = true)

        service.advance(AdvanceRunoutCommand(tableId.value))

        assertEquals(3, handStore.find(tableId)!!.board.size)
        assertEquals(1, handStore.saves)
        assertEquals(1, eventPublisher.events.count { it is HandBroadcastRequested })
    }

    @Test
    fun `리버 다음 걸음은 쇼다운해서 정산하고 핸드를 지운다`() {
        val tableId = tableWithHand(allIn = true)

        repeat(4) { service.advance(AdvanceRunoutCommand(tableId.value)) }

        assertNull(handStore.find(tableId))
        assertEquals(2_000L, lastHandResults.find(tableId)!!.payouts.sumOf { it.amount })
        assertEquals(Chips.of(2_000), tables.findById(tableId)!!.occupiedSeats().fold(Chips.ZERO) { a, s -> a + s.stack })
    }
}
