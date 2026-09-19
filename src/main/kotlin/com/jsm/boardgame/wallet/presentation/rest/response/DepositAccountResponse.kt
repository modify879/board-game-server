package com.jsm.boardgame.wallet.presentation.rest.response

data class DepositAccountResponse(
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
)
