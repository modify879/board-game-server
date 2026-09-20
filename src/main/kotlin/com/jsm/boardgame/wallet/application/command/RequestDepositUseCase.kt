package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.model.DepositRequestId

interface RequestDepositUseCase {
    fun request(command: RequestDepositCommand): DepositRequestId
}

data class RequestDepositCommand(val userId: Long, val amount: Long)
