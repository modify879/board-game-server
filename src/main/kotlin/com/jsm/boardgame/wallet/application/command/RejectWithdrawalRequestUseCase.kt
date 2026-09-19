package com.jsm.boardgame.wallet.application.command

interface RejectWithdrawalRequestUseCase {
    fun reject(command: RejectWithdrawalRequestCommand)
}
