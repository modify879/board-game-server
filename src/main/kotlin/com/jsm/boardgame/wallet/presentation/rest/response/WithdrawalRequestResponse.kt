package com.jsm.boardgame.wallet.presentation.rest.response

import com.jsm.boardgame.wallet.application.query.view.WithdrawalRequestView
import java.time.Instant

data class WithdrawalRequestResponse(
    val id: Long,
    val userId: Long,
    val amount: Long,
    val status: String,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
    val requestedAt: Instant,
    val processedAt: Instant?,
    val rejectionReason: String?,
) {
    companion object {
        fun from(view: WithdrawalRequestView): WithdrawalRequestResponse =
            WithdrawalRequestResponse(
                id = view.id,
                userId = view.userId,
                amount = view.amount,
                status = view.status,
                bankName = view.bankName,
                accountNumber = view.accountNumber,
                accountHolder = view.accountHolder,
                requestedAt = view.requestedAt,
                processedAt = view.processedAt,
                rejectionReason = view.rejectionReason,
            )
    }
}
