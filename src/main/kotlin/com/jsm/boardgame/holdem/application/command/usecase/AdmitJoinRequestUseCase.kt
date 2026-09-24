package com.jsm.boardgame.holdem.application.command.usecase

interface AdmitJoinRequestUseCase {
    fun admit(command: AdmitJoinRequestCommand)
}

data class AdmitJoinRequestCommand(val tableId: Long, val userId: Long)
