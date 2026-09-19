package com.jsm.boardgame.wallet.application.command

interface RejectDepositRequestUseCase {
    fun reject(command: RejectDepositRequestCommand)
}
