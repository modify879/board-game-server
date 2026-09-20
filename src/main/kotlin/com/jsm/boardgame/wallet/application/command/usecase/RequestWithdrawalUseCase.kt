package com.jsm.boardgame.wallet.application.command.usecase

import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId

interface RequestWithdrawalUseCase {
    fun request(command: RequestWithdrawalCommand): WithdrawalRequestId
}

data class RequestWithdrawalCommand(
    val userId: Long,
    val amount: Long,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
)
