package com.jsm.boardgame.wallet.presentation.rest.request

import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestCommand

/** creditedAmount 가 없으면(본문 자체가 없어도) 요청 금액 그대로 승인한다. */
data class ApproveDepositRequestRequest(val creditedAmount: Long? = null) {
    fun toCommand(requestId: Long, adminUserId: Long): ApproveDepositRequestCommand =
        ApproveDepositRequestCommand(requestId = requestId, adminUserId = adminUserId, creditedAmount = creditedAmount)
}
