package com.jsm.boardgame.holdem.application.command.usecase

interface ResumeHandUseCase {
    fun resume(command: ResumeHandCommand)
}

data class ResumeHandCommand(val tableId: Long)
