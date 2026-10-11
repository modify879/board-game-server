package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AddChipsCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.InsufficientBalanceException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.ChipAddOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AddChipsFakeTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]
    override fun findByUserId(userId: UserId): HoldemTable? = stored.values.find { it.seatOf(userId) != null }
    override fun findAllSeatedUserIds(): List<UserId> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = stored.values.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
        )
        stored[id.value] = saved
        return saved
    }
}

private class AddChipsFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()
    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class AddChipsFakeWallet : WalletBalance, WalletTransfer {
    data class ToGameCall(val userId: UserId, val amount: Long, val tableId: TableId)

    var balance = 0L
    val toGameCalls = mutableListOf<ToGameCall>()

    override fun balanceOf(userId: UserId): Long = balance
    override fun toGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        toGameCalls += ToGameCall(userId, amount, tableId)
        balance -= amount
    }
    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) = error("칩 추가는 fromGame 을 부르지 않는다")
}

private class AddChipsFakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}

class AddChipsServiceTest {

    private val tables = AddChipsFakeTableRepository()
    private val handStore = AddChipsFakeHandStore()
    private val wallet = AddChipsFakeWallet()
    private val events = AddChipsFakeEventPublisher()
    private val service = AddChipsService(tables, handStore, wallet, wallet, events)
    private val user = UserId(1L)

    private fun seatedTable(stack: Long = 10_000): TableId {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(user, Chips.of(stack))
        return tables.save(table).id!!
    }

    @Test
    fun `앉아 있지 않으면 NOT_SEATED`() {
        val e = assertFailsWith<NotSeatedException> { service.addChips(AddChipsCommand(1, 1_000)) }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    @Test
    fun `핸드가 진행 중이면 HAND_IN_PROGRESS 이고 지갑을 건드리지 않는다`() {
        val tableId = seatedTable()
        handStore.save(
            tableId,
            Hand.start(mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it }),
        )
        wallet.balance = 50_000

        val e = assertFailsWith<HandInProgressException> { service.addChips(AddChipsCommand(1, 1_000)) }

        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
        assertTrue(wallet.toGameCalls.isEmpty())
    }

    @Test
    fun `스택이 100BB 를 넘게 되면 CHIP_ADD_OUT_OF_RANGE 이고 지갑을 건드리지 않는다`() {
        val tableId = seatedTable(stack = 10_000)
        wallet.balance = 50_000

        val e = assertFailsWith<ChipAddOutOfRangeException> { service.addChips(AddChipsCommand(1, 10_100)) }

        assertEquals(HoldemErrorCode.CHIP_ADD_OUT_OF_RANGE, e.errorCode)
        assertTrue(wallet.toGameCalls.isEmpty())
        assertEquals(Chips.of(10_000), tables.findById(tableId)!!.seatAt(1)!!.stack)
    }

    @Test
    fun `지갑 잔액이 모자라면 INSUFFICIENT_BALANCE`() {
        seatedTable()
        wallet.balance = 500

        val e = assertFailsWith<InsufficientBalanceException> { service.addChips(AddChipsCommand(1, 1_000)) }

        assertEquals(HoldemErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
        assertTrue(wallet.toGameCalls.isEmpty())
    }

    @Test
    fun `칩 단위가 아니면 CHIPS_NOT_UNIT`() {
        seatedTable()
        wallet.balance = 50_000

        val e = assertFailsWith<com.jsm.boardgame.holdem.domain.exception.InvalidChipsException> {
            service.addChips(AddChipsCommand(1, 1_050))
        }

        assertEquals(HoldemErrorCode.CHIPS_NOT_UNIT, e.errorCode)
    }

    @Test
    fun `정상이면 지갑에서 옮기고 스택을 저장하고 공개 뷰 브로드캐스트를 요청한다`() {
        val tableId = seatedTable(stack = 10_000)
        wallet.balance = 50_000

        service.addChips(AddChipsCommand(1, 5_000))

        assertEquals(Chips.of(15_000), tables.findById(tableId)!!.seatAt(1)!!.stack)
        assertEquals(listOf(AddChipsFakeWallet.ToGameCall(user, 5_000, tableId)), wallet.toGameCalls)
        val broadcast = events.events.filterIsInstance<HandBroadcastRequested>().single()
        assertEquals(tableId, broadcast.tableId)
        assertNull(broadcast.hand)
    }
}
