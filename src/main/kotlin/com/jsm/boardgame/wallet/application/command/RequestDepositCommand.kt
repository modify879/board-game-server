package com.jsm.boardgame.wallet.application.command

data class RequestDepositCommand(val userId: Long, val amount: Long)
