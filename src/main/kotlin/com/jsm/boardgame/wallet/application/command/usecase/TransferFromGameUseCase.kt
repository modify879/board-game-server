package com.jsm.boardgame.wallet.application.command.usecase

interface TransferFromGameUseCase {
    fun transfer(command: TransferFromGameCommand)
}

/** 게임 테이블 → 지갑 캐시아웃. */
data class TransferFromGameCommand(
    val userId: Long,
    val amount: Long,
    val gameTableId: Long,
    val memo: String?,
)
