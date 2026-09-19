package com.jsm.boardgame.wallet.application.command

data class CancelWithdrawalRequestCommand(val requestId: Long, val requesterUserId: Long)
