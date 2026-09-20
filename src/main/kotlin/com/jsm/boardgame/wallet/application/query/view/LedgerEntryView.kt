package com.jsm.boardgame.wallet.application.query.view

import java.time.Instant

class LedgerEntryView(
    val id: Long,
    val type: String,
    val amount: Long,
    val balanceAfter: Long,
    val referenceType: String,
    val referenceId: Long,
    val memo: String?,
    val occurredAt: Instant,
)
