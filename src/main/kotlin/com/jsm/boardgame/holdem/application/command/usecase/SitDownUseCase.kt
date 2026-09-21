package com.jsm.boardgame.holdem.application.command.usecase

interface SitDownUseCase {
    fun sitDown(command: SitDownCommand)
}

data class SitDownCommand(val tableId: Long, val userId: Long, val seatNo: Int, val buyIn: Long)
