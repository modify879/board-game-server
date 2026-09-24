package com.jsm.boardgame.wallet.application.command.usecase

interface TransferFromGameUseCase {
    fun transfer(command: TransferFromGameCommand)
}

data class TransferFromGameCommand(
    val userId: Long,
    val amount: Long,
    val gameTableId: Long,
    val memo: String?,
)
