package com.jsm.boardgame.holdem.application.command.usecase

interface StartScheduledHandUseCase {
    fun start(command: StartScheduledHandCommand)
}

data class StartScheduledHandCommand(val tableId: Long)
