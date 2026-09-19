package com.jsm.boardgame.wallet.application.query

import java.time.Instant

class DepositRequestView(
    val id: Long,
    val userId: Long,
    val requestedAmount: Long,
    val status: String,
    val creditedAmount: Long?,
    val requestedAt: Instant,
    val processedAt: Instant?,
    val rejectionReason: String?,
)
