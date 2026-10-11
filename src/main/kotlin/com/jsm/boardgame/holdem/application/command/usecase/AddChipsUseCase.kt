package com.jsm.boardgame.holdem.application.command.usecase

/** 호출자가 그 테이블의 실행기 스레드 위에 있어야 한다. */
interface AddChipsUseCase {
    fun addChips(command: AddChipsCommand)
}

data class AddChipsCommand(val userId: Long, val amount: Long)
