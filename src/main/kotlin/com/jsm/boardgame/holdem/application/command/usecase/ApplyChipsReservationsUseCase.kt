package com.jsm.boardgame.holdem.application.command.usecase

/** 호출자가 그 테이블의 실행기 스레드 위에 있어야 한다. 핸드가 진행 중이면 아무것도 하지 않는다. */
interface ApplyChipsReservationsUseCase {
    fun apply(command: ApplyChipsReservationsCommand)
}

data class ApplyChipsReservationsCommand(val tableId: Long)
