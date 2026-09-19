package com.jsm.boardgame.wallet.application.command

interface CancelDepositRequestUseCase {
    fun cancel(command: CancelDepositRequestCommand)
}
