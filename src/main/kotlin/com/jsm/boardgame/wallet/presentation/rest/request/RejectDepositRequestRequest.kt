package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.RejectDepositRequestCommand

data class RejectDepositRequestRequest(val reason: String) {
    fun toCommand(requestId: Long, adminUserId: Long): RejectDepositRequestCommand =
        RejectDepositRequestCommand(requestId = requestId, adminUserId = adminUserId, reason = reason)
}
