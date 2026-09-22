package com.jsm.boardgame.wallet.application.command.usecase

interface TransferToGameUseCase {
    fun transfer(command: TransferToGameCommand)
}

/** 지갑 → 게임 테이블 바이인. gameTableId 는 게임 테이블 id 다 — wallet 은 어느 게임인지 모른다(memo 가 나른다). */
data class TransferToGameCommand(
    val userId: Long,
    val amount: Long,
    val gameTableId: Long,
    val memo: String?,
)
