package com.jsm.boardgame.holdem.application.command.usecase

interface ExpireRevealUseCase {
    fun expire(command: ExpireRevealCommand)
}

data class ExpireRevealCommand(val tableId: Long)
