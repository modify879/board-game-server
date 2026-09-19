package com.jsm.boardgame.wallet.application.command

data class ApproveWithdrawalRequestCommand(val requestId: Long, val adminUserId: Long)
