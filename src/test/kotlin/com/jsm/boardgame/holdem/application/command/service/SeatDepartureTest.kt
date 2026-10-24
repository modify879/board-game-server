package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.FakeWalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatDepartureTest {

    private val departures = FakeRecentDepartures()
    private val wallet = FakeWalletTransfer()
    private val seatDeparture = SeatDeparture(departures, wallet)
    private val tableId = TableId(7L)

    @Test
    fun `양수 스택은 재착석 의무를 기록하고 지갑으로 돌려보낸다`() {
        val table = HoldemTable.create("테스트 테이블", TestTablePolicy)
        table.sitDown(UserId(1), Chips.of(8_000))

        val returned = seatDeparture.standUp(tableId, table, UserId(1))

        assertEquals(Chips.of(8_000), returned)
        assertEquals(listOf(UserId(1) to Chips.of(8_000)), departures.recorded)
        assertEquals(listOf(FakeWalletTransfer.Call(UserId(1), 8_000L, tableId, "holdem")), wallet.fromGameCalls)
    }

    @Test
    fun `0칩이면 의무 해제만 기록하고 지갑을 부르지 않는다`() {
        val table = HoldemTable.create("테스트 테이블", TestTablePolicy)
        val seat = table.sitDown(UserId(1), Chips.of(8_000))
        table.applyStacks(mapOf(seat.seatNo to Chips.ZERO))

        val returned = seatDeparture.standUp(tableId, table, UserId(1))

        assertEquals(Chips.ZERO, returned)
        assertEquals(listOf(UserId(1) to Chips.ZERO), departures.recorded)
        assertTrue(wallet.fromGameCalls.isEmpty())
    }
}
