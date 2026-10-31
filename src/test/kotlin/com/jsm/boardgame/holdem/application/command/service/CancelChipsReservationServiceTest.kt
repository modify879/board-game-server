package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.application.command.usecase.CancelChipsReservationCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.port.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.model.UserId
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CancelChipsReservationServiceTest {

    private val tables = FakeHoldemTableRepository()
    private val events = mutableListOf<Any>()
    private val service = CancelChipsReservationService(tables, FakeHandStore(), ApplicationEventPublisher { events += it })

    @Test
    fun `예약을 취소하면 풀리고 브로드캐스트하며 예약이 없을 때 취소는 조용히 끝난다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        table.reserveChips(UserId(1), Chips.of(1_000))
        val tableId = tables.save(table).id!!

        service.cancel(CancelChipsReservationCommand(userId = 1))
        service.cancel(CancelChipsReservationCommand(userId = 1))

        assertNull(tables.findById(tableId)!!.seatAt(1)!!.chipsReservation)
        assertEquals(1, events.count { it is HandBroadcastRequested })
    }

    @Test
    fun `앉아 있지 않으면 예약 취소는 NOT_SEATED`() {
        val e = assertFailsWith<NotSeatedException> { service.cancel(CancelChipsReservationCommand(userId = 999)) }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }
}
