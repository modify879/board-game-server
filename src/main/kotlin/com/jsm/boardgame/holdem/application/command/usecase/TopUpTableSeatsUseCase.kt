package com.jsm.boardgame.holdem.application.command.usecase

/** 호출자가 그 테이블의 실행기 스레드 위에 있어야 한다. 정산 뒤·입금 뒤 합산이 쓴다. */
interface TopUpTableSeatsUseCase {
    fun topUp(command: TopUpTableSeatsCommand)
}

data class TopUpTableSeatsCommand(val tableId: Long)
