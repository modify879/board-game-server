package com.jsm.boardgame.wallet.application.command.usecase

interface RequestWithdrawalUseCase {
    fun request(command: RequestWithdrawalCommand): Long
}

data class RequestWithdrawalCommand(
    val userId: Long,
    val amount: Long,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
    val idempotencyKey: String?,
)
