package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TopUpFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()

    override fun findById(id: TableId): HoldemTable? = stored[id.value]
    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }
    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = stored.values.mapNotNull { it.id }
    override fun save(table: HoldemTable): HoldemTable {
        stored[table.id!!.value] = table
        return table
    }
}

private class TopUpFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class TopUpFakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}

private class TopUpFakeWalletTransfer : WalletTransfer {
    val balances = mutableMapOf<Long, Long>()
    val allToGameCalls = mutableListOf<Long>()

    override fun allToGame(userId: Long, tableId: Long, memo: String?): Long {
        allToGameCalls += userId
        return balances.remove(userId) ?: 0
    }

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) = error("충전 합산은 fromGame 을 부르지 않는다")
}

private class TopUpFakeTableExecutor : TableExecutor {
    val calledTables = mutableListOf<TableId>()

    override fun <T> call(tableId: TableId, task: () -> T): T {
        calledTables += tableId
        return task()
    }

    override fun post(tableId: TableId, task: () -> Unit) = error("충전 합산은 post 를 쓰지 않는다")

    override fun remove(tableId: TableId) {}
}

class TopUpSeatFromWalletServiceTest {

    private val tables = TopUpFakeHoldemTableRepository()
    private val handStore = TopUpFakeHandStore()
    private val walletTransfer = TopUpFakeWalletTransfer()
    private val eventPublisher = TopUpFakeEventPublisher()
    private val executor = TopUpFakeTableExecutor()
    private val service = TopUpSeatFromWalletService(
        tables, handStore, walletTransfer, eventPublisher, executor, TransactionOperations.withoutTransaction(),
    )

    private fun seatedTable(): TableId {
        val table = tables.save(HoldemTable.reconstitute(TableId(1), "t", Chips.of(100), Chips.of(200), null, emptyMap()))
        table.sitDown(1000L, Chips.of(1_000))
        table.sitDown(2000L, Chips.of(1_000))
        return table.id!!
    }

    @Test
    fun `핸드가 없으면 지갑 잔액 전부를 스택에 합치고 공개 뷰 브로드캐스트를 올린다`() {
        val tableId = seatedTable()
        walletTransfer.balances[1000L] = 500L

        service.topUp(TopUpSeatFromWalletCommand(1000L))

        assertEquals(Chips.of(1_500), tables.findById(tableId)!!.seatOf(1000L)!!.stack)
        assertEquals(Chips.of(1_000), tables.findById(tableId)!!.seatOf(2000L)!!.stack)
        assertEquals(listOf(tableId), executor.calledTables)
        val event = eventPublisher.events.filterIsInstance<HandBroadcastRequested>().single()
        assertEquals(tableId, event.tableId)
        assertEquals(null, event.hand)
    }

    @Test
    fun `핸드가 진행 중이면 스택을 건드리지 않고 지갑에서 옮기지도 않는다`() {
        val tableId = seatedTable()
        val hand = Hand.start(
            mapOf(1 to Chips.of(1_000), 2 to Chips.of(1_000)), 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it },
        )
        handStore.save(tableId, hand)
        walletTransfer.balances[1000L] = 500L

        service.topUp(TopUpSeatFromWalletCommand(1000L))

        assertEquals(Chips.of(1_000), tables.findById(tableId)!!.seatOf(1000L)!!.stack)
        assertTrue(walletTransfer.allToGameCalls.isEmpty())
        assertTrue(eventPublisher.events.isEmpty())
    }

    @Test
    fun `앉아 있지 않은 사용자면 아무것도 하지 않는다`() {
        seatedTable()
        walletTransfer.balances[9999L] = 500L

        service.topUp(TopUpSeatFromWalletCommand(9999L))

        assertTrue(executor.calledTables.isEmpty())
        assertTrue(walletTransfer.allToGameCalls.isEmpty())
        assertTrue(eventPublisher.events.isEmpty())
    }

    @Test
    fun `지갑 잔액이 0이면 스택은 그대로이고 브로드캐스트도 하지 않는다`() {
        val tableId = seatedTable()

        service.topUp(TopUpSeatFromWalletCommand(1000L))

        assertEquals(Chips.of(1_000), tables.findById(tableId)!!.seatOf(1000L)!!.stack)
        assertEquals(listOf(1000L), walletTransfer.allToGameCalls)
        assertTrue(eventPublisher.events.isEmpty())
    }
}
