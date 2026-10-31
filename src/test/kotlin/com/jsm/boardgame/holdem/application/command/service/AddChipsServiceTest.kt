package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.repository.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.repository.FakeHandStore
import com.jsm.boardgame.holdem.application.command.usecase.AddChipsCommand
import com.jsm.boardgame.holdem.application.command.usecase.AddChipsResult
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.InsufficientBalanceException
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
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private val tables = FakeHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val wallet = AddChipsFakeWallet()
    private val events = AddChipsFakeEventPublisher()
    private val service = AddChipsService(tables, handStore, wallet, wallet, events)
    private val user = UserId(1L)

    private fun seatedTable(stack: Long = 10_000): TableId {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(user, Chips.of(stack))
        return tables.save(table).id!!
    }

    @Test
    fun `앉아 있지 않으면 NOT_SEATED`() {
        val e = assertFailsWith<NotSeatedException> { service.addChips(AddChipsCommand(1, 1_000)) }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    private fun startHand(tableId: TableId, vararg seatNos: Int) {
        handStore.save(
            tableId,
            Hand.start(seatNos.associateWith { Chips.of(10_000) }, buttonSeatNo = seatNos[0], smallBlindSeatNo = seatNos[0], bigBlindSeatNo = seatNos[1], TestTablePolicy.smallBlind, TestTablePolicy.bigBlind, Shuffler { it }),
        )
    }

    @Test
    fun `핸드 참가자는 핸드 중에 예약만 걸리고 지갑은 그대로이며 덮어쓴다`() {
        val tableId = seatedTable()
        startHand(tableId, 1, 2)
        wallet.balance = 50_000

        assertEquals(AddChipsResult.RESERVED, service.addChips(AddChipsCommand(1, 1_000)))
        assertEquals(AddChipsResult.RESERVED, service.addChips(AddChipsCommand(1, 99_900))) // 한도·잔액은 적용 때 본다

        val seat = tables.findById(tableId)!!.seatAt(1)!!
        assertEquals(Chips.of(99_900), seat.chipsReservation)
        assertEquals(Chips.of(10_000), seat.stack)
        assertTrue(wallet.toGameCalls.isEmpty())
        val broadcast = events.events.filterIsInstance<HandBroadcastRequested>().first()
        assertEquals(false, broadcast.handProgressed)
        assertNotNull(broadcast.hand)
    }

    @Test
    fun `핸드 도중 앉아 참가하지 않은 좌석은 핸드 중에도 바로 더한다`() {
        val tableId = seatedTable() // 좌석 1
        startHand(tableId, 2, 3) // 좌석 1 은 이번 핸드의 참가자가 아니다
        wallet.balance = 50_000

        assertEquals(AddChipsResult.ADDED, service.addChips(AddChipsCommand(1, 5_000)))

        val seat = tables.findById(tableId)!!.seatAt(1)!!
        assertEquals(Chips.of(15_000), seat.stack)
        assertNull(seat.chipsReservation)
        assertEquals(1, wallet.toGameCalls.size)
    }

    @Test
    fun `핸드 도중 참가하지 않은 좌석이 바로 더해도 브로드캐스트는 진행 중 핸드를 싣고 핸드 진행으로 알리지 않는다`() {
        val tableId = seatedTable()
        startHand(tableId, 2, 3)
        wallet.balance = 50_000

        assertEquals(AddChipsResult.ADDED, service.addChips(AddChipsCommand(1, 5_000)))

        val broadcast = events.events.filterIsInstance<HandBroadcastRequested>().single()
        assertNotNull(broadcast.hand)
        assertEquals(false, broadcast.handProgressed)
    }

    @Test
    fun `핸드 중 칩 단위가 아니면 CHIPS_NOT_UNIT 이고 예약하지 않는다`() {
        val tableId = seatedTable()
        startHand(tableId, 1, 2)

        val e = assertFailsWith<com.jsm.boardgame.holdem.domain.exception.InvalidChipsException> {
            service.addChips(AddChipsCommand(1, 1_050))
        }

        assertEquals(HoldemErrorCode.CHIPS_NOT_UNIT, e.errorCode)
        assertNull(tables.findById(tableId)!!.seatAt(1)!!.chipsReservation)
    }

    @Test
    fun `핸드 중 0 칩 예약은 CHIP_ADD_OUT_OF_RANGE`() {
        val tableId = seatedTable()
        startHand(tableId, 1, 2)

        val e = assertFailsWith<ChipAddOutOfRangeException> { service.addChips(AddChipsCommand(1, 0)) }

        assertEquals(HoldemErrorCode.CHIP_ADD_OUT_OF_RANGE, e.errorCode)
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
