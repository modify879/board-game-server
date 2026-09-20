package com.jsm.boardgame.wallet.application.command.usecase

interface RejectDepositRequestUseCase {
    fun reject(command: RejectDepositRequestCommand)
}

data class RejectDepositRequestCommand(val requestId: Long, val adminUserId: Long, val reason: String)
