package com.jsm.boardgame.holdem.application.command.usecase

interface ExpireTurnUseCase {
    fun expire(command: ExpireTurnCommand)
}

data class ExpireTurnCommand(val tableId: Long, val seatNo: Int)
