package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class SeatsTopUpFakeTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()

    override fun findById(id: TableId): HoldemTable? = stored[id.value]
    override fun findByUserId(userId: UserId): HoldemTable? = stored.values.find { it.seatOf(userId) != null }
    override fun findAllSeatedUserIds(): List<UserId> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = stored.values.mapNotNull { it.id }
    override fun save(table: HoldemTable): HoldemTable {
        stored[table.id!!.value] = table
        return table
    }
}

private class SeatsTopUpFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class SeatsTopUpFakeWalletTransfer : WalletTransfer {
    val balances = mutableMapOf<Long, Long>()
    val allToGameCalls = mutableListOf<Long>()
    val failingUserIds = mutableSetOf<Long>()

    override fun allToGame(userId: UserId, tableId: TableId, memo: String?): Long {
        allToGameCalls += userId.value
        if (userId.value in failingUserIds) error("지갑 행 락 대기 시간 초과")
        return balances.remove(userId.value) ?: 0
    }

    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) = error("충전 합산은 fromGame 을 부르지 않는다")
}

private class SeatsTopUpFakeWalletBalance(private val wallet: SeatsTopUpFakeWalletTransfer) : WalletBalance {
    override fun balanceOf(userId: UserId): Long = wallet.balances[userId.value] ?: 0
}

private class SeatsTopUpFakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}

class TableSeatsTopUpTest {

    private val tables = SeatsTopUpFakeTableRepository()
    private val handStore = SeatsTopUpFakeHandStore()
    private val wallet = SeatsTopUpFakeWalletTransfer()
    private val eventPublisher = SeatsTopUpFakeEventPublisher()
    private val tableSeatsTopUp = TableSeatsTopUp(tables, SeatsTopUpFakeWalletBalance(wallet), SeatTopUper(tables, handStore, wallet))
    private val service = TopUpTableSeatsService(tables, tableSeatsTopUp, eventPublisher, TransactionOperations.withoutTransaction())

    private fun seatedTable(): TableId {
        val table = tables.save(HoldemTable.reconstitute(TableId(1), "t", Chips.of(100), Chips.of(200), null, emptyMap()))
        table.sitDown(UserId(1000L), Chips.of(1_000))
        table.sitDown(UserId(2000L), Chips.of(1_000))
        table.sitDown(UserId(3000L), Chips.of(1_000))
        return table.id!!
    }

    private fun stackOf(tableId: TableId, userId: Long) = tables.findById(tableId)!!.seatOf(UserId(userId))!!.stack

    @Test
    fun `핸드가 없으면 입금된 좌석만 합치고 공개 뷰 브로드캐스트를 한 번 올린다`() {
        val tableId = seatedTable()
        wallet.balances[1000L] = 500L
        wallet.balances[3000L] = 700L

        service.topUp(TopUpTableSeatsCommand(tableId.value))

        assertEquals(Chips.of(1_500), stackOf(tableId, 1000L))
        assertEquals(Chips.of(1_000), stackOf(tableId, 2000L))
        assertEquals(Chips.of(1_700), stackOf(tableId, 3000L))
        val event = eventPublisher.events.filterIsInstance<HandBroadcastRequested>().single()
        assertEquals(tableId, event.tableId)
        assertEquals(null, event.hand)
    }

    @Test
    fun `지갑 잔액이 0이면 이체를 부르지 않고 브로드캐스트도 하지 않는다 - 지갑 락을 잡지 않는다`() {
        val tableId = seatedTable()

        service.topUp(TopUpTableSeatsCommand(tableId.value))

        assertTrue(wallet.allToGameCalls.isEmpty())
        assertTrue(eventPublisher.events.isEmpty())
    }

    @Test
    fun `한 좌석의 이체가 던져도 다른 좌석은 합쳐지고 실패한 좌석은 스택을 지킨다`() {
        val tableId = seatedTable()
        wallet.balances[1000L] = 500L
        wallet.balances[2000L] = 600L
        wallet.failingUserIds += 1000L

        service.topUp(TopUpTableSeatsCommand(tableId.value))

        assertEquals(Chips.of(1_000), stackOf(tableId, 1000L))
        assertEquals(Chips.of(1_600), stackOf(tableId, 2000L))
        assertEquals(1, eventPublisher.events.filterIsInstance<HandBroadcastRequested>().size)
    }

    @Test
    fun `핸드가 진행 중이면 스택을 건드리지 않고 지갑에서 옮기지도 않는다`() {
        val tableId = seatedTable()
        handStore.save(
            tableId,
            Hand.start(mapOf(1 to Chips.of(1_000), 2 to Chips.of(1_000)), 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it }),
        )
        wallet.balances[1000L] = 500L

        service.topUp(TopUpTableSeatsCommand(tableId.value))

        assertEquals(Chips.of(1_000), stackOf(tableId, 1000L))
        assertTrue(wallet.allToGameCalls.isEmpty())
        assertTrue(eventPublisher.events.isEmpty())
    }
}
