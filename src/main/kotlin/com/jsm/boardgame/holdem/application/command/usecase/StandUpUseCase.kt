package com.jsm.boardgame.holdem.application.command.usecase

interface StandUpUseCase {
    fun standUp(command: StandUpCommand): StandUpResult
}

/** [after] 는 핸드 중 예약의 종류(`LeaveReservation` 상수명). 즉시 기립에서는 쓰지 않는다. */
data class StandUpCommand(val userId: Long, val after: String = "SPECTATE")

/** [STOOD_UP] 은 바로 일어섰다는 뜻, [RESERVED] 는 핸드가 진행 중이라 끝난 뒤 일어나도록 예약했다는 뜻이다. */
enum class StandUpResult { STOOD_UP, RESERVED }
