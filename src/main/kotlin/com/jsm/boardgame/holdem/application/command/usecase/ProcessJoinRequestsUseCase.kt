package com.jsm.boardgame.holdem.application.command.usecase

interface ProcessJoinRequestsUseCase {
    fun process(command: ProcessJoinRequestsCommand)
}

data class ProcessJoinRequestsCommand(val tableId: Long)
