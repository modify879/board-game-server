package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeCopyingHoldemTableRepository
import com.jsm.boardgame.holdem.application.port.FakeWalletTransfer
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.port.FakeNextHandCountdown
import com.jsm.boardgame.holdem.domain.model.placeButtonAtFirstSeat
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CancelHandServiceTest {

    private val tables = FakeCopyingHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val walletTransfer = FakeWalletTransfer()
    private val countdown = FakeNextHandCountdown()
    private val events = mutableListOf<Any>()
    private val eventPublisher = ApplicationEventPublisher { events += it }
    private val departures = FakeRecentDepartures()
    private val service = CancelHandService(tables, handStore, walletTransfer, countdown, LeaveReservations(departures, walletTransfer), eventPublisher)

    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in buyIns) {
            table.sitDown(userId = UserId(seatNo.toLong()), buyIn = Chips.of(buyIn))
        }
        table = tables.save(table)
        return table.id!!
    }

    private fun startAndStoreHand(tableId: TableId, startingStacks: Map<Int, Chips>): Hand {
        val table = tables.findById(tableId)!!
        table.placeButtonAtFirstSeat()
        val buttonSeatNo = table.buttonSeatNo!!
        val seatNos = startingStacks.keys.sorted()
        fun nextSeatNo(from: Int): Int = seatNos[(seatNos.indexOf(from) + 1) % seatNos.size]
        val (smallBlindSeatNo, bigBlindSeatNo) = if (seatNos.size == 2) {
            buttonSeatNo to nextSeatNo(buttonSeatNo)
        } else {
            val sb = nextSeatNo(buttonSeatNo)
            sb to nextSeatNo(sb)
        }
        val hand = Hand.start(startingStacks, buttonSeatNo, smallBlindSeatNo, bigBlindSeatNo, table.smallBlind, table.bigBlind, identityShuffler)
        handStore.save(tableId, hand)
        return hand
    }

    @Test
    fun `취소하면 좌석 스택이 시작 스택으로 정확히 되돌아간다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val startingStacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000))
        val hand = startAndStoreHand(tableId, startingStacks)
        // 스택을 흔들어 놓는다 - 취소가 hand.stackOf() 가 아니라 startingStacks 로 되돌리는지 검증하려면
        // 취소 시점에 스택이 이미 달라져 있어야 한다.
        hand.act(hand.toActSeatNo!!, BettingAction.Call)

        service.cancel(CancelHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(Chips.of(10_000), savedTable.seatAt(1)!!.stack)
        assertEquals(Chips.of(10_000), savedTable.seatAt(2)!!.stack)
        assertEquals(Chips.of(10_000), savedTable.seatAt(3)!!.stack)
    }

    @Test
    fun `취소하면 진행 중 핸드 행이 삭제된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)))

        service.cancel(CancelHandCommand(tableId.value))

        assertNull(handStore.find(tableId))
    }

    @Test
    fun `취소해도 좌석은 유지된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)))

        service.cancel(CancelHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(2, savedTable.occupiedSeats().size)
    }

    @Test
    fun `취소해도 퇴장 예약한 좌석은 기립해 시작 스택을 지갑으로 돌려받는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)))
        tables.findById(tableId)!!.also { it.reserveLeave(UserId(2L)); tables.save(it) }

        service.cancel(CancelHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertNull(savedTable.seatOf(UserId(2L)))
        assertEquals(2, savedTable.occupiedSeats().size)
        assertEquals(listOf(UserId(2L) to 10_000L), walletTransfer.fromGameCalls.map { it.userId to it.amount })
        assertEquals(listOf(UserId(2L) to Chips.of(10_000)), departures.recorded)
    }

    @Test
    fun `진행 중 핸드가 없으면 조용히 끝난다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        service.cancel(CancelHandCommand(tableId.value))

        val savedTable = tables.findById(tableId)!!
        assertEquals(Chips.of(10_000), savedTable.seatAt(1)!!.stack)
        assertEquals(Chips.of(10_000), savedTable.seatAt(2)!!.stack)
        assertEquals(2, savedTable.occupiedSeats().size)
    }

    @Test
    fun `취소 전후 스택 총합이 보존된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val totalBefore = tables.findById(tableId)!!.occupiedSeats().sumOf { it.stack.amount }
        val hand = startAndStoreHand(
            tableId,
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
        )
        hand.act(hand.toActSeatNo!!, BettingAction.Call)

        service.cancel(CancelHandCommand(tableId.value))

        val totalAfter = tables.findById(tableId)!!.occupiedSeats().sumOf { it.stack.amount }
        assertEquals(totalBefore, totalAfter)
    }

    @Test
    fun `핸드 도중 폴드하고 기립한 좌석은 투입액을 지갑으로 돌려받고 나머지는 시작 스택으로 돌아간다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val hand = startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)))
        // 버튼(1) 콜 -> SB(2) 폴드(100 투입) 후 즉시 기립. ExpireTurnService 와 같은 절차.
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Fold)
        val table = tables.findById(tableId)!!
        hand.recordDeparture(2, UserId(2L))
        table.applyStacks(mapOf(2 to hand.stackOf(2)))
        val standUpReturn = table.standUp(UserId(2L))
        tables.save(table)

        service.cancel(CancelHandCommand(tableId.value))

        val saved = tables.findById(tableId)!!
        assertNull(saved.seatAt(2))
        assertEquals(Chips.of(10_000), saved.seatAt(1)!!.stack)
        assertEquals(Chips.of(10_000), saved.seatAt(3)!!.stack)
        val refund = walletTransfer.fromGameCalls.single()
        assertEquals(UserId(2L), refund.userId)
        assertEquals(100L, refund.amount)
        assertEquals(tableId, refund.tableId)
        // 기립 때 받은 잔여 스택 + 취소 환불 = 시작 스택
        assertEquals(10_000L, standUpReturn.amount + refund.amount)
        assertEquals(30_000L - 10_000L, saved.occupiedSeats().sumOf { it.stack.amount })
    }

    @Test
    fun `이탈한 좌석이 없으면 지갑을 부르지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)))

        service.cancel(CancelHandCommand(tableId.value))

        assertTrue(walletTransfer.fromGameCalls.isEmpty())
    }

    @Test
    fun `취소 뒤 후보가 2명 이상이면 카운트다운을 다시 걸고 참가 요청 처리를 알린다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)))

        service.cancel(CancelHandCommand(tableId.value))

        assertEquals(listOf(tableId), countdown.restarted)
        assertTrue(events.any { it is JoinRequestsDue && it.tableId == tableId })
    }

    @Test
    fun `취소 뒤 후보가 2명 미만이면 카운트다운을 걸지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val hand = startAndStoreHand(tableId, mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)))
        hand.act(1, BettingAction.Fold)
        hand.act(2, BettingAction.Fold)
        val table = tables.findById(tableId)!!
        // 폴드한 두 좌석이 기립하고 나면 남는 후보는 1명이다.
        for (seatNo in listOf(1, 2)) {
            hand.recordDeparture(seatNo, UserId(seatNo.toLong()))
            table.applyStacks(mapOf(seatNo to hand.stackOf(seatNo)))
            table.standUp(UserId(seatNo.toLong()))
        }
        tables.save(table)

        service.cancel(CancelHandCommand(tableId.value))

        assertTrue(countdown.restarted.isEmpty())
        assertTrue(events.any { it is JoinRequestsDue })
    }
}
