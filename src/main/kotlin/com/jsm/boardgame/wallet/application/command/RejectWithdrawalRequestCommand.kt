package com.jsm.boardgame.wallet.application.command

data class RejectWithdrawalRequestCommand(val requestId: Long, val adminUserId: Long, val reason: String)
