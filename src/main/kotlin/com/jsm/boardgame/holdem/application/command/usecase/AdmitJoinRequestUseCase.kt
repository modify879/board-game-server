package com.jsm.boardgame.holdem.application.command.usecase

interface AdmitJoinRequestUseCase {
    fun admit(command: AdmitJoinRequestCommand): AdmitJoinRequestResult
}

data class AdmitJoinRequestCommand(val tableId: Long, val userId: Long, val buyIn: Long, val postBlindImmediately: Boolean)

sealed interface AdmitJoinRequestResult {
    data class Seated(val seatNo: Int) : AdmitJoinRequestResult
    data object Blocked : AdmitJoinRequestResult
}
