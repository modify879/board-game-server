package com.jsm.boardgame.holdem.application.command.usecase

interface StandUpUseCase {
    fun standUp(command: StandUpCommand)
}

data class StandUpCommand(val userId: Long)
