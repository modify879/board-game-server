package com.jsm.boardgame.holdem.application.command.usecase

interface TopUpSeatFromWalletUseCase {
    fun topUp(command: TopUpSeatFromWalletCommand)
}

data class TopUpSeatFromWalletCommand(val userId: Long)
