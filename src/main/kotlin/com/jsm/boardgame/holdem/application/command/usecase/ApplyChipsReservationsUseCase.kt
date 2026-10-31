package com.jsm.boardgame.holdem.application.command.usecase

/** 호출자가 그 테이블의 실행기 스레드 위에 있어야 한다. 핸드가 진행 중이면 아무것도 하지 않는다. */
interface ApplyChipsReservationsUseCase {
    fun apply(command: ApplyChipsReservationsCommand)
}

data class ApplyChipsReservationsCommand(val tableId: Long)

/** 좌석 하나의 칩 추가 예약을 자기 트랜잭션으로 적용하거나 버린다. [ApplyChipsReservationsUseCase] 의 내부 단계다. */
interface ApplySeatChipsReservationUseCase {
    fun apply(command: ApplySeatChipsReservationCommand)

    /** 적용이 실패했을 때 예약만 지우고, 스택이 0 이면 일으켜 세운다 — 0칩 좌석이 영영 남지 않게 한다. */
    fun drop(command: ApplySeatChipsReservationCommand)
}

data class ApplySeatChipsReservationCommand(val tableId: Long, val userId: Long)
