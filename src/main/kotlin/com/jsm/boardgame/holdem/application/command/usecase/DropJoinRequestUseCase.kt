package com.jsm.boardgame.holdem.application.command.usecase

interface DropJoinRequestUseCase {
    fun drop(command: DropJoinRequestCommand)
}

data class DropJoinRequestCommand(val tableId: Long, val userId: Long)
