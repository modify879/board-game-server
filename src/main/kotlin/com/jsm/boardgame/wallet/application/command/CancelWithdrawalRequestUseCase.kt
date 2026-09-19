package com.jsm.boardgame.wallet.application.command

interface CancelWithdrawalRequestUseCase {
    fun cancel(command: CancelWithdrawalRequestCommand)
}
