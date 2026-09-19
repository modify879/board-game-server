package com.jsm.boardgame.wallet.presentation.rest.response

data class WithdrawalRequestCreatedResponse(val requestId: Long) {
    companion object {
        fun from(requestId: Long): WithdrawalRequestCreatedResponse = WithdrawalRequestCreatedResponse(requestId = requestId)
    }
}
