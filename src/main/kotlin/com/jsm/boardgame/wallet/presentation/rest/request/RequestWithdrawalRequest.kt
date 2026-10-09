package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalCommand

data class RequestWithdrawalRequest(
    val amount: Long,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
) {
    fun toCommand(userId: Long, idempotencyKey: String?): RequestWithdrawalCommand =
        RequestWithdrawalCommand(
            userId = userId,
            amount = amount,
            bankName = bankName,
            accountNumber = accountNumber,
            accountHolder = accountHolder,
            idempotencyKey = idempotencyKey,
        )
}
