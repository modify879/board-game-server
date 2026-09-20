package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.RequestWithdrawalCommand

data class RequestWithdrawalRequest(
    val amount: Long,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
) {
    fun toCommand(userId: Long): RequestWithdrawalCommand =
        RequestWithdrawalCommand(
            userId = userId,
            amount = amount,
            bankName = bankName,
            accountNumber = accountNumber,
            accountHolder = accountHolder,
        )
}
