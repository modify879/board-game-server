package com.jsm.boardgame.holdem.application.command.usecase

interface PlayActionUseCase {
    fun play(command: PlayActionCommand)
}

data class PlayActionCommand(
    val tableId: Long,
    val userId: Long,
    val action: String,
    val raiseToAmount: Long?,
)
