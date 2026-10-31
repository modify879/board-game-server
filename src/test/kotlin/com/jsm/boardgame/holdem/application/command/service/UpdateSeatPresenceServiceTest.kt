package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.repository.FakeHandStore
import com.jsm.boardgame.holdem.domain.repository.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.domain.model.Hand
import org.springframework.context.ApplicationEventPublisher
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpdateSeatPresenceServiceTest {

    private val tables = FakeHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val events = mutableListOf<Any>()
    private val service = UpdateSeatPresenceService(tables, handStore, ApplicationEventPublisher { events += it })

    @Test
    fun `착석한 사용자의 좌석 연결 상태를 바꾼다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        val saved = tables.save(table)

        service.update(UpdateSeatPresenceCommand(userId = 1, presence = "DISCONNECTED"))

        assertEquals(SeatPresence.DISCONNECTED, tables.findById(saved.id!!)!!.seatAt(1)!!.presence)
    }

    @Test
    fun `연결 상태가 바뀌면 진행 중 핸드와 함께 표시 전용 브로드캐스트를 정확히 한 번 낸다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        table.sitDown(UserId(2), Chips.of(8_000))
        val saved = tables.save(table)
        val hand = Hand.start(
            mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)),
            buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2,
            smallBlind = TestTablePolicy.smallBlind, bigBlind = TestTablePolicy.bigBlind, shuffler = { it },
        )
        handStore.put(saved.id!!.value, hand)

        service.update(UpdateSeatPresenceCommand(userId = 1, presence = "DISCONNECTED"))

        val event = events.filterIsInstance<HandBroadcastRequested>().single()
        assertEquals(saved.id, event.tableId)
        assertSame(hand, event.hand)
        assertEquals(false, event.handProgressed)
    }

    @Test
    fun `같은 연결 상태면 아무것도 내지 않는다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy))
        table.sitDown(UserId(1), Chips.of(8_000))
        tables.save(table)

        service.update(UpdateSeatPresenceCommand(userId = 1, presence = "SEATED"))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `미착석 사용자의 이벤트는 조용히 끝낸다`() {
        service.update(UpdateSeatPresenceCommand(userId = 999, presence = "DISCONNECTED"))
        // 예외 없이 끝나면 통과.
    }
}
