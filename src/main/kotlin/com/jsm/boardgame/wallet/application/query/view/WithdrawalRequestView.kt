package com.jsm.boardgame.wallet.application.query.view

import java.time.Instant

class WithdrawalRequestView(
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
)
