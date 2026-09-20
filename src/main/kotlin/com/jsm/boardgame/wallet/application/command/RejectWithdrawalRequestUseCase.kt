package com.jsm.boardgame.wallet.application.command

interface RejectWithdrawalRequestUseCase {
    fun reject(command: RejectWithdrawalRequestCommand)
}

data class RejectWithdrawalRequestCommand(val requestId: Long, val adminUserId: Long, val reason: String)
