package com.jsm.boardgame.holdem.application.command.usecase

interface CancelHandUseCase {
    fun cancel(command: CancelHandCommand)
}

data class CancelHandCommand(val tableId: Long)
