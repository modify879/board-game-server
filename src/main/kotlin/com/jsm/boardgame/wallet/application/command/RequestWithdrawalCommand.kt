package com.jsm.boardgame.wallet.application.command

data class RequestWithdrawalCommand(
    val userId: Long,
    val amount: Long,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
)
