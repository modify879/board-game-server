package com.jsm.boardgame.wallet.application.command.usecase

interface TransferBalanceToGameUseCase {
    /** 옮긴 금액을 돌려준다. 잔액이 0이면 원장을 남기지 않고 0이다. */
    fun transfer(command: TransferBalanceToGameCommand): Long
}

/** 지갑 잔액 전부 → 게임 테이블. gameTableId 의미는 [TransferToGameCommand] 와 같다. */
data class TransferBalanceToGameCommand(
    val userId: Long,
    val gameTableId: Long,
    val memo: String?,
)
