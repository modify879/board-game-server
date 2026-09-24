package com.jsm.boardgame.holdem.application.command.usecase

interface StartHandUseCase {
    fun start(command: StartHandCommand)
}

data class StartHandCommand(val tableId: Long, val userId: Long)
