package com.jsm.boardgame.holdem.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `postBlindImmediately 를 true 로 착석하면 즉시 참가하되 진입료를 빚진다`() {
        val seat = Seat.of(seatNo = 1, userId = 1L, stack = Chips.of(10_000), postBlindImmediately = true)
        assertFalse(seat.awaitingBigBlind)
        assertTrue(seat.owesImmediatePost)
    }

    @Test
    fun `postBlindImmediately 를 생략하거나 false 로 착석하면 BB 를 기다리고 진입료가 없다`() {
        val default = Seat.of(seatNo = 1, userId = 1L, stack = Chips.of(10_000))
        assertTrue(default.awaitingBigBlind)
        assertFalse(default.owesImmediatePost)

        val explicit = Seat.of(seatNo = 1, userId = 1L, stack = Chips.of(10_000), postBlindImmediately = false)
        assertTrue(explicit.awaitingBigBlind)
        assertFalse(explicit.owesImmediatePost)
    }

    @Test
    fun `clearAwaitingBigBlind 와 consumeImmediatePost 는 각각의 플래그만 끈다`() {
        val awaiting = Seat.of(seatNo = 1, userId = 1L, stack = Chips.of(10_000), postBlindImmediately = false)
        awaiting.clearAwaitingBigBlind()
        assertFalse(awaiting.awaitingBigBlind)

        val owing = Seat.of(seatNo = 1, userId = 1L, stack = Chips.of(10_000), postBlindImmediately = true)
        owing.consumeImmediatePost()
        assertFalse(owing.owesImmediatePost)
    }
}
