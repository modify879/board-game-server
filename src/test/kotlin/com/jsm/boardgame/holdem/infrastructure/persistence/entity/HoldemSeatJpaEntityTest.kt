package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.domain.model.LeaveReservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HoldemSeatJpaEntityTest {

    private fun entity(leaveAfterHand: Boolean, leaveReservation: LeaveReservation?) = HoldemSeatJpaEntity(
        tableId = 1, seatNo = 1, userId = 1, stack = 100, presence = "SEATED",
        awaitingBigBlind = false, owesImmediatePost = false,
        leaveAfterHand = leaveAfterHand, leaveReservation = leaveReservation,
    )

    @Test
    fun `종류 컬럼이 없는 예전 예약 행은 SPECTATE 로 복원된다`() {
        assertEquals(LeaveReservation.SPECTATE, entity(true, null).toDomain().leaveReservation)
    }

    @Test
    fun `종류 컬럼이 있으면 그대로 복원되고 예약이 없으면 null 이다`() {
        assertEquals(LeaveReservation.LEAVE, entity(true, LeaveReservation.LEAVE).toDomain().leaveReservation)
        assertNull(entity(false, null).toDomain().leaveReservation)
    }
}
