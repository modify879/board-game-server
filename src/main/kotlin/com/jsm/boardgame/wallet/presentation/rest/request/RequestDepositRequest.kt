package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand

data class RequestDepositRequest(val amount: Long) {
    fun toCommand(userId: Long): RequestDepositCommand = RequestDepositCommand(userId = userId, amount = amount)
}
