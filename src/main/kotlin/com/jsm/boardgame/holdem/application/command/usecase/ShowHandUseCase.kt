package com.jsm.boardgame.holdem.application.command.usecase

interface ShowHandUseCase {
    fun show(command: ShowHandCommand)
}

data class ShowHandCommand(val tableId: Long, val userId: Long)
