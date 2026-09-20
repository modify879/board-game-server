package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.usecase.RejectWithdrawalRequestCommand

data class RejectWithdrawalRequestRequest(val reason: String) {
    fun toCommand(requestId: Long, adminUserId: Long): RejectWithdrawalRequestCommand =
        RejectWithdrawalRequestCommand(requestId = requestId, adminUserId = adminUserId, reason = reason)
}
