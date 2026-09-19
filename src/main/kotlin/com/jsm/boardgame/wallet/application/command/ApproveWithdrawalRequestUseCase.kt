package com.jsm.boardgame.wallet.application.command

interface ApproveWithdrawalRequestUseCase {
    fun approve(command: ApproveWithdrawalRequestCommand)
}
