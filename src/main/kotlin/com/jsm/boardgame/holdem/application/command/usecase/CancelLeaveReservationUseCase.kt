package com.jsm.boardgame.holdem.application.command.usecase

/** 퇴장 예약을 푼다. 예약이 없어도 조용히 끝난다. */
interface CancelLeaveReservationUseCase {
    fun cancel(command: CancelLeaveReservationCommand)
}

data class CancelLeaveReservationCommand(val userId: Long)
