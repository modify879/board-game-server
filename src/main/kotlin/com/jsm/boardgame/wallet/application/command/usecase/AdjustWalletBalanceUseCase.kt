package com.jsm.boardgame.wallet.application.command.usecase

interface AdjustWalletBalanceUseCase {
    fun adjust(command: AdjustWalletBalanceCommand)
}

/**
 * amount 는 부호 있는 증감액이다. 양수=지급, 음수=회수.
 * idempotencyKey 는 Idempotency-Key 헤더 값이다. 재시도로 온 같은 키의 두 번째 호출을
 * ADJUSTMENT_ALREADY_APPLIED 로 막는다 — AdjustWalletBalanceService 와 AdjustmentKeyRegistry 를 본다.
 */
data class AdjustWalletBalanceCommand(
    val targetUserId: Long,
    val amount: Long,
    val reason: String,
    val adminUserId: Long,
    val idempotencyKey: String?,
)
