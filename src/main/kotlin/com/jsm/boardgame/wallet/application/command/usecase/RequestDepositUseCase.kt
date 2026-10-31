package com.jsm.boardgame.wallet.application.command.usecase

interface RequestDepositUseCase {
    fun request(command: RequestDepositCommand): Long
}

data class RequestDepositCommand(val userId: Long, val amount: Long, val idempotencyKey: String?)
