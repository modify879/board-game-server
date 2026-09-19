package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidAmountException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode

/** 칩의 최소 단위가 100 이므로 지갑 금액도 100원 단위로만 움직인다. */
const val MONEY_UNIT = 100L

@JvmInline
value class Money private constructor(val amount: Long) {

    // 금액 상한이 없어 오버플로가 원리적으로 가능하다. 조용히 음수로 감기면 원장이 틀어지므로,
    // 시스템이 표현할 수 없는 값은 조용히 넘기지 않고 그대로 터뜨린다.
    operator fun plus(other: Money): Money = Money(Math.addExact(amount, other.amount))

    operator fun minus(other: Money): Money {
        val result = amount - other.amount
        if (result < 0) {
            throw InvalidAmountException(WalletErrorCode.AMOUNT_NEGATIVE, "차감 결과가 음수입니다: $amount - ${other.amount}")
        }
        return Money(result)
    }

    fun isLessThan(other: Money): Boolean = amount < other.amount

    fun isZero(): Boolean = amount == 0L

    fun isMultipleOf(unit: Long): Boolean = amount % unit == 0L

    companion object {
        val ZERO = Money(0)

        fun of(amount: Long): Money {
            if (amount < 0) {
                throw InvalidAmountException(WalletErrorCode.AMOUNT_NEGATIVE, "금액은 음수일 수 없습니다: $amount")
            }
            return Money(amount)
        }

        /** 영속 복원 전용 — 검증하지 않는다. 사용자 입력에는 [of] 를 써라. */
        fun reconstitute(amount: Long): Money = Money(amount)
    }
}
