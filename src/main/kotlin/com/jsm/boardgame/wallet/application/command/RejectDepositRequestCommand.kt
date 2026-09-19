package com.jsm.boardgame.wallet.application.command

data class RejectDepositRequestCommand(val requestId: Long, val adminUserId: Long, val reason: String)
