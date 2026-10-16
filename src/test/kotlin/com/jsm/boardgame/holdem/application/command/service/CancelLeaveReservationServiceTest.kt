package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.application.command.usecase.CancelLeaveReservationCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.LeaveReservation
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class CancelLeaveFakeHandStore : HandStore {
    override fun find(tableId: TableId): Hand? = null
    override fun save(tableId: TableId, hand: Hand) {}
    override fun remove(tableId: TableId) {}
    override fun findAllInProgress(): List<TableId> = emptyList()
}

class CancelLeaveReservationServiceTest {

    private val tables = FakeHoldemTableRepository()
    private val events = mutableListOf<Any>()
    private val service = CancelLeaveReservationService(tables, CancelLeaveFakeHandStore(), ApplicationEventPublisher { events += it })

    private fun reservedTable(): TableId {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(UserId(1), Chips.of(8_000))
        table.reserveLeave(UserId(1), LeaveReservation.SPECTATE)
        return tables.save(table).id!!
    }

    @Test
    fun `예약을 취소하면 풀리고 예약이 없을 때 취소는 조용히 끝난다`() {
        val tableId = reservedTable()

        service.cancel(CancelLeaveReservationCommand(userId = 1))
        service.cancel(CancelLeaveReservationCommand(userId = 1))

        assertEquals(false, tables.findById(tableId)!!.seatAt(1)!!.leaveReserved)
        assertEquals(1, events.count { it is HandBroadcastRequested })
    }

    @Test
    fun `앉아 있지 않으면 예약 취소는 NOT_SEATED`() {
        assertFailsWith<NotSeatedException> { service.cancel(CancelLeaveReservationCommand(userId = 999)) }
    }
}
