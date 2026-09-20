package com.jsm.boardgame.wallet.application.command.usecase

interface AdjustWalletBalanceUseCase {
    fun adjust(command: AdjustWalletBalanceCommand)
}

/** amount 는 부호 있는 증감액이다. 양수=지급, 음수=회수. */
data class AdjustWalletBalanceCommand(
    val targetUserId: Long,
    val amount: Long,
    val reason: String,
    val adminUserId: Long,
)
