package com.jsm.boardgame.holdem.application.command.usecase

/** 칩 추가 예약을 푼다. 예약이 없어도 조용히 끝난다. */
interface CancelChipsReservationUseCase {
    fun cancel(command: CancelChipsReservationCommand)
}

data class CancelChipsReservationCommand(val userId: Long)
