package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand

data class RequestDepositRequest(val amount: Long) {
    fun toCommand(userId: Long, idempotencyKey: String?): RequestDepositCommand =
        RequestDepositCommand(userId = userId, amount = amount, idempotencyKey = idempotencyKey)
}
