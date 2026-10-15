package com.jsm.boardgame.holdem.application.command.usecase

interface StandUpUseCase {
    fun standUp(command: StandUpCommand): StandUpResult
}

data class StandUpCommand(val userId: Long)

/** [STOOD_UP] 은 바로 일어섰다는 뜻, [RESERVED] 는 핸드가 진행 중이라 끝난 뒤 일어나도록 예약했다는 뜻이다. */
enum class StandUpResult { STOOD_UP, RESERVED }
