package com.jsm.boardgame.holdem.application.command.usecase

interface AdvanceRunoutUseCase {
    fun advance(command: AdvanceRunoutCommand)
}

data class AdvanceRunoutCommand(val tableId: Long)
