package com.jsm.boardgame.wallet.presentation.rest.response

data class DepositRequestCreatedResponse(
    val requestId: Long,
    val depositAccount: DepositAccountResponse,
) {
    companion object {
        fun from(requestId: Long, depositAccount: DepositAccountResponse): DepositRequestCreatedResponse =
            DepositRequestCreatedResponse(requestId = requestId, depositAccount = depositAccount)
    }
}
