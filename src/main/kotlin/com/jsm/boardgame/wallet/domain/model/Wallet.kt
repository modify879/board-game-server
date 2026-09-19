package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.exception.InvalidAmountException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import java.time.Instant

class Wallet private constructor(
    val id: WalletId?,
    // user 컨텍스트의 UserId 를 쓰지 않는다 — 컨텍스트끼리 domain 을 참조하지 않는다(규칙 1).
    val userId: Long,
    balance: Money,
    val version: Long,
) {
    var balance: Money = balance
        private set

    /**
     * 지갑 잔액을 바꾸는 유일한 입구다. credit/debit 두 메서드로 가르지 않는 이유는
     * 잔액만 바꾸고 원장을 빠뜨리는 호출이 존재할 수 없게 하기 위해서다 —
     * 반환값이 곧 저장해야 할 원장 엔트리다.
     */
    fun record(
        type: LedgerEntryType,
        amount: Money,
        reference: LedgerReference,
        memo: String? = null,
        at: Instant,
    ): LedgerEntry {
        val walletId = id ?: error("저장되지 않은 지갑에는 원장을 붙일 수 없다")

        if (amount.isZero()) {
            throw InvalidAmountException(WalletErrorCode.AMOUNT_NOT_POSITIVE, "amount 는 0일 수 없습니다")
        }
        if (type.direction == LedgerDirection.DEBIT && balance.isLessThan(amount)) {
            throw InsufficientBalanceException("잔액 부족: balance=$balance, amount=$amount")
        }

        balance = when (type.direction) {
            LedgerDirection.CREDIT -> balance + amount
            LedgerDirection.DEBIT -> balance - amount
        }

        return LedgerEntry.record(
            walletId = walletId,
            type = type,
            amount = amount,
            balanceAfter = balance,
            reference = reference,
            memo = memo,
            occurredAt = at,
        )
    }

    companion object {
        /** 신규 개설. 잔액 0, 아직 저장되지 않아 id 는 null 이다. */
        fun open(userId: Long): Wallet = Wallet(id = null, userId = userId, balance = Money.ZERO, version = 0)

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(id: WalletId, userId: Long, balance: Money, version: Long): Wallet =
            Wallet(id = id, userId = userId, balance = balance, version = version)
    }
}
