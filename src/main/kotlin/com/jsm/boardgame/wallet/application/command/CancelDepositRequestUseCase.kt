package com.jsm.boardgame.wallet.application.command

interface CancelDepositRequestUseCase {
    fun cancel(command: CancelDepositRequestCommand)
}

data class CancelDepositRequestCommand(val requestId: Long, val requesterUserId: Long)
