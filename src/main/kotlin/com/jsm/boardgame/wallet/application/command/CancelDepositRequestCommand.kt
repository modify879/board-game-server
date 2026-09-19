package com.jsm.boardgame.wallet.application.command

data class CancelDepositRequestCommand(val requestId: Long, val requesterUserId: Long)
