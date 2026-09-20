package com.jsm.boardgame.wallet.presentation.rest.response

import com.jsm.boardgame.wallet.application.query.LedgerEntryView
import java.time.Instant

data class LedgerEntryResponse(
    val id: Long,
    val type: String,
    val amount: Long,
    val balanceAfter: Long,
    val referenceType: String,
    val referenceId: Long,
    val memo: String?,
    val occurredAt: Instant,
) {
    companion object {
        fun from(view: LedgerEntryView): LedgerEntryResponse =
            LedgerEntryResponse(
                id = view.id,
                type = view.type,
                amount = view.amount,
                balanceAfter = view.balanceAfter,
                referenceType = view.referenceType,
                referenceId = view.referenceId,
                memo = view.memo,
                occurredAt = view.occurredAt,
            )
    }
}
