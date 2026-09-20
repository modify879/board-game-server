package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand

/** amount 는 부호 있는 증감액이다. 양수=지급, 음수=회수. */
data class AdjustWalletBalanceRequest(val amount: Long, val reason: String) {
    fun toCommand(targetUserId: Long, adminUserId: Long): AdjustWalletBalanceCommand =
        AdjustWalletBalanceCommand(targetUserId = targetUserId, amount = amount, reason = reason, adminUserId = adminUserId)
}
