package com.jsm.boardgame.wallet.application.command.usecase

interface CancelWithdrawalRequestUseCase {
    fun cancel(command: CancelWithdrawalRequestCommand)
}

data class CancelWithdrawalRequestCommand(val requestId: Long, val requesterUserId: Long)
