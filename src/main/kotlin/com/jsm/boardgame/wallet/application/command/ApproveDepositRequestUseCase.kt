package com.jsm.boardgame.wallet.application.command

interface ApproveDepositRequestUseCase {
    fun approve(command: ApproveDepositRequestCommand)
}

/** creditedAmount 가 null 이면 요청 금액 그대로 승인한다. */
data class ApproveDepositRequestCommand(val requestId: Long, val adminUserId: Long, val creditedAmount: Long?)
