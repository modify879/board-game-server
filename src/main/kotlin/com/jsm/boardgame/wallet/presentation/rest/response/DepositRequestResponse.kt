package com.jsm.boardgame.wallet.presentation.rest.response

import com.jsm.boardgame.wallet.application.query.view.DepositRequestView
import java.time.Instant

data class DepositRequestResponse(
    val id: Long,
    val userId: Long,
    val requestedAmount: Long,
    val status: String,
    val creditedAmount: Long?,
    val requestedAt: Instant,
    val processedAt: Instant?,
    val rejectionReason: String?,
) {
    companion object {
        fun from(view: DepositRequestView): DepositRequestResponse =
            DepositRequestResponse(
                id = view.id,
                userId = view.userId,
                requestedAmount = view.requestedAmount,
                status = view.status,
                creditedAmount = view.creditedAmount,
                requestedAt = view.requestedAt,
                processedAt = view.processedAt,
                rejectionReason = view.rejectionReason,
            )
    }
}
