package com.jsm.boardgame.wallet.domain.model

// Money 가 음수를 못 가지므로, 부호를 타입이 나르지 않으면 원장 합산으로 잔액을 검증할 수 없다.
enum class LedgerEntryType(val direction: LedgerDirection) {
    DEPOSIT(LedgerDirection.CREDIT),
    WITHDRAWAL_HOLD(LedgerDirection.DEBIT),
    WITHDRAWAL_REFUND(LedgerDirection.CREDIT),
    ADMIN_ADJUSTMENT_CREDIT(LedgerDirection.CREDIT),
    ADMIN_ADJUSTMENT_DEBIT(LedgerDirection.DEBIT),
}
