package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeNextHandCountdown
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.port.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.port.FakeLastHandResultStore
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import com.jsm.boardgame.holdem.application.command.usecase.StandUpResult
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.FakeFoldWinnerCards
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.LeaveReservation
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StandUpFakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}

private class StandUpFakeWalletTransfer : WalletTransfer {
    data class FromGameCall(val userId: Long, val amount: Long, val tableId: TableId, val memo: String?)

    val fromGameCalls = mutableListOf<FromGameCall>()

    override fun toGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        error("StandUp 는 toGame 을 부르지 않는다")
    }

    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        fromGameCalls += FromGameCall(userId.value, amount, tableId, memo)
    }
}

class StandUpServiceTest {

    private val tables = FakeHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val walletTransfer = StandUpFakeWalletTransfer()
    private val eventPublisher = StandUpFakeEventPublisher()
    private val countdown = FakeNextHandCountdown()
    private val handStarter = HandStarter(tables, handStore, FakeLastHandResultStore(), FakeFoldWinnerCards(), { _, _, _ -> Shuffler { it } }, eventPublisher, countdown)
    private val departures = FakeRecentDepartures()
    private val service = StandUpService(tables, handStore, walletTransfer, handStarter, departures, eventPublisher)

    @Test
    fun `기립하면 좌석이 비고 스택이 지갑으로 돌아간다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        val saved = tables.save(table)

        service.standUp(StandUpCommand(userId = 1))

        assertNull(tables.findById(saved.id!!)!!.seatAt(1))
        val call = walletTransfer.fromGameCalls.single()
        assertEquals(1L, call.userId)
        assertEquals(8_000L, call.amount)
        assertEquals(saved.id, call.tableId)
        assertEquals(listOf(UserId(1) to Chips.of(8_000)), departures.recorded)
        assertTrue(eventPublisher.events.any { it is JoinRequestsDue && it.tableId == saved.id })
    }

    @Test
    fun `앉은 적 없는 사용자가 기립하면 NOT_SEATED`() {
        val e = assertFailsWith<NotSeatedException> {
            service.standUp(StandUpCommand(userId = 999))
        }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    private fun tableWithHandInProgress(): TableId {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        val saved = tables.save(table)
        handStore.save(
            saved.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, TestTablePolicy.smallBlind, TestTablePolicy.bigBlind, Shuffler { it }),
        )
        return saved.id!!
    }

    @Test
    fun `핸드가 진행 중이면 기립 대신 퇴장을 예약하고 공개 뷰를 브로드캐스트한다`() {
        val tableId = tableWithHandInProgress()

        val result = service.standUp(StandUpCommand(userId = 1))

        assertEquals(StandUpResult.RESERVED, result)
        assertTrue(tables.findById(tableId)!!.seatAt(1)!!.leaveReserved)
        assertEquals(0, walletTransfer.fromGameCalls.size)
        assertEquals(1, eventPublisher.events.count { it is HandBroadcastRequested })
    }

    @Test
    fun `다른 종류로 다시 요청하면 종류가 바뀌고 브로드캐스트한다`() {
        val tableId = tableWithHandInProgress()
        service.standUp(StandUpCommand(userId = 1))

        assertEquals(StandUpResult.RESERVED, service.standUp(StandUpCommand(userId = 1, after = "LEAVE")))

        assertEquals(LeaveReservation.LEAVE, tables.findById(tableId)!!.seatAt(1)!!.leaveReservation)
        assertEquals(2, eventPublisher.events.count { it is HandBroadcastRequested })
    }

    @Test
    fun `같은 종류로 다시 요청하면 브로드캐스트하지 않는다`() {
        tableWithHandInProgress()
        service.standUp(StandUpCommand(userId = 1))

        assertEquals(StandUpResult.RESERVED, service.standUp(StandUpCommand(userId = 1)))

        assertEquals(1, eventPublisher.events.count { it is HandBroadcastRequested })
    }

    @Test
    fun `스택이 0 이면 기립은 되지만 지갑 이체는 불리지 않는다`() {
        val zeroStackSeat = Seat.reconstitute(seatNo = 1, userId = UserId(1), stack = Chips.ZERO, presence = SeatPresence.SEATED)
        val table = HoldemTable.reconstitute(
            id = TableId(1),
            name = "테스트 테이블",
            policy = TestTablePolicy,
            buttonSeatNo = null,
            seats = mapOf(1 to zeroStackSeat),
        )
        tables.stored[1] = table

        service.standUp(StandUpCommand(userId = 1))

        assertEquals(0, walletTransfer.fromGameCalls.size)
        assertNull(tables.findById(TableId(1))!!.seatAt(1))
    }

    @Test
    fun `기립으로 후보가 2명 미만이 되면 다음 핸드 카운트다운이 취소된다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        table.sitDown(UserId(2), Chips.of(8_000))
        tables.save(table)

        service.standUp(StandUpCommand(userId = 1))

        assertTrue(countdown.cancelled.contains(table.id))
    }

    @Test
    fun `기립 후에도 후보가 2명 이상이면 카운트다운을 건드리지 않는다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        table.sitDown(UserId(2), Chips.of(8_000))
        table.sitDown(UserId(3), Chips.of(8_000))
        tables.save(table)

        service.standUp(StandUpCommand(userId = 1))

        assertTrue(countdown.cancelled.isEmpty())
        assertTrue(countdown.restarted.isEmpty())
    }
}

