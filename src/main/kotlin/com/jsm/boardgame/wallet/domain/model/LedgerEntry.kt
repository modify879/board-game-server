package com.jsm.boardgame.wallet.domain.model

import java.time.Instant

@JvmInline
value class LedgerEntryId(val value: Long)

enum class LedgerDirection { CREDIT, DEBIT }

// Money 가 음수를 못 가지므로, 부호를 타입이 나르지 않으면 원장 합산으로 잔액을 검증할 수 없다.
enum class LedgerEntryType(val direction: LedgerDirection) {
    DEPOSIT(LedgerDirection.CREDIT),
    WITHDRAWAL_HOLD(LedgerDirection.DEBIT),
    WITHDRAWAL_REFUND(LedgerDirection.CREDIT),
    ADMIN_ADJUSTMENT_CREDIT(LedgerDirection.CREDIT),
    ADMIN_ADJUSTMENT_DEBIT(LedgerDirection.DEBIT),
    GAME_BUY_IN(LedgerDirection.DEBIT),
    GAME_CASH_OUT(LedgerDirection.CREDIT),
}

class LedgerEntry private constructor(
    val id: LedgerEntryId?,
    val walletId: WalletId,
    val type: LedgerEntryType,
    val amount: Money,
    val balanceAfter: Money,
    val reference: LedgerReference,
    val memo: String?,
    val occurredAt: Instant,
) {
    companion object {
        // 잔액을 안 건드린 채 원장만 만드는 경로를 막으려는 의도 표시다. 단일 모듈이라
        // internal 은 애플리케이션 전체를 뜻해 강제력이 없다 — 실제 보장은 Wallet.record() 가
        // 잔액 변경의 유일한 입구라는 점이고, 못 박으려면 ArchUnit 이 필요하다.
        internal fun record(
            walletId: WalletId,
            type: LedgerEntryType,
            amount: Money,
            balanceAfter: Money,
            reference: LedgerReference,
            memo: String?,
            occurredAt: Instant,
        ): LedgerEntry = LedgerEntry(
            id = null,
            walletId = walletId,
            type = type,
            amount = amount,
            balanceAfter = balanceAfter,
            reference = reference,
            memo = memo,
            occurredAt = occurredAt,
        )

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(
            id: LedgerEntryId,
            walletId: WalletId,
            type: LedgerEntryType,
            amount: Money,
            balanceAfter: Money,
            reference: LedgerReference,
            memo: String?,
            occurredAt: Instant,
        ): LedgerEntry = LedgerEntry(
            id = id,
            walletId = walletId,
            type = type,
            amount = amount,
            balanceAfter = balanceAfter,
            reference = reference,
            memo = memo,
            occurredAt = occurredAt,
        )
    }
}
