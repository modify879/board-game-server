package com.jsm.boardgame.holdem.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SeatTest {

    @Test
    fun `of 로 만든 좌석은 SEATED 상태로 시작한다`() {
        val seat = Seat.of(seatNo = 1, userId = 1L, stack = Chips.of(10_000))
        assertEquals(SeatPresence.SEATED, seat.presence)
        assertEquals(Chips.of(10_000), seat.stack)
    }

    @Test
    fun `applyStack 은 스택을 바꾼다`() {
        val seat = Seat.of(seatNo = 1, userId = 1L, stack = Chips.of(10_000))
        seat.applyStack(Chips.of(5_000))
        assertEquals(Chips.of(5_000), seat.stack)
    }

    @Test
    fun `reconstitute 는 검증 없이 임의의 presence 와 스택을 받는다`() {
        val seat = Seat.reconstitute(
            seatNo = 3,
            userId = 2L,
            stack = Chips.reconstitute(-100),
            presence = SeatPresence.DISCONNECTED,
        )
        assertEquals(SeatPresence.DISCONNECTED, seat.presence)
        assertEquals(-100L, seat.stack.amount)
    }
}
