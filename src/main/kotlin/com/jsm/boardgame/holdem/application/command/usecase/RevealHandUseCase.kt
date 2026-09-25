package com.jsm.boardgame.holdem.application.command.usecase

interface RevealHandUseCase {
    fun reveal(command: RevealHandCommand)
}

data class RevealHandCommand(
    val tableId: Long,
    val userId: Long,
    val action: String,
)
