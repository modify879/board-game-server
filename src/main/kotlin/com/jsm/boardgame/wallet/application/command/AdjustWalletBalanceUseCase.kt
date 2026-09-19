package com.jsm.boardgame.wallet.application.command

interface AdjustWalletBalanceUseCase {
    fun adjust(command: AdjustWalletBalanceCommand)
}
