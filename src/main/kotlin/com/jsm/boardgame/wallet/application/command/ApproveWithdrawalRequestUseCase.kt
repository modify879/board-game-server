package com.jsm.boardgame.wallet.application.command

interface ApproveWithdrawalRequestUseCase {
    fun approve(command: ApproveWithdrawalRequestCommand)
}

data class ApproveWithdrawalRequestCommand(val requestId: Long, val adminUserId: Long)
