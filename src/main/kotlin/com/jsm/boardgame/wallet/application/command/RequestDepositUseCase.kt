package com.jsm.boardgame.wallet.application.command

interface RequestDepositUseCase {
    fun request(command: RequestDepositCommand): Long
}
