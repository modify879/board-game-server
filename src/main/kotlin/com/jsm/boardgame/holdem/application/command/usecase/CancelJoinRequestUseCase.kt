package com.jsm.boardgame.holdem.application.command.usecase

interface CancelJoinRequestUseCase {
    fun cancel(command: CancelJoinRequestCommand)
}

data class CancelJoinRequestCommand(val tableId: Long, val userId: Long)
