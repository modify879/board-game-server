package com.jsm.boardgame.wallet.application.command

interface ApproveDepositRequestUseCase {
    fun approve(command: ApproveDepositRequestCommand)
}
