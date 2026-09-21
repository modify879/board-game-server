package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.InvalidChipsException

/**
 * 칩의 최소 단위. 베팅·레이즈·팟 분배 금액이 전부 이 배수라는 것이 도메인 불변식이다.
 * 2,500 을 둘이 나누면 1,250 이 아니라 1,200 씩 + 나머지 100 인 이유가 이것이다.
 */
const val CHIP_UNIT = 100L

/** holdem 자기 재화다. wallet 의 Money 를 쓰지 않고 다른 게임과 공유하지도 않는다(규칙 7). */
@JvmInline
value class Chips private constructor(val amount: Long) : Comparable<Chips> {

    // 조용히 음수로 감기면 팟이 틀어지므로 표현할 수 없는 값은 그대로 터뜨린다.
    operator fun plus(other: Chips): Chips = Chips(Math.addExact(amount, other.amount))

    operator fun minus(other: Chips): Chips {
        val result = amount - other.amount
        if (result < 0) {
            throw InvalidChipsException(HoldemErrorCode.CHIPS_NEGATIVE, "차감 결과가 음수입니다: $amount - ${other.amount}")
        }
        return Chips(result)
    }

    operator fun times(count: Int): Chips = Chips(Math.multiplyExact(amount, count.toLong()))

    override fun compareTo(other: Chips): Int = amount.compareTo(other.amount)

    fun isZero(): Boolean = amount == 0L

    fun isPositive(): Boolean = amount > 0L

    /**
     * [ways] 명이 나눠 가질 때 1인분과 나머지. 1인분은 [CHIP_UNIT] 으로 내림하므로
     * 나머지도 [CHIP_UNIT] 의 배수이고, 나머지 칩을 버튼 왼쪽부터 한 단위씩 돌릴 수 있다.
     */
    fun splitEvenly(ways: Int): Pair<Chips, Chips> {
        if (ways <= 0) error("나눌 대상이 없다: ways=$ways")
        val each = Chips(amount / ways / CHIP_UNIT * CHIP_UNIT)
        return each to Chips(amount - each.amount * ways)
    }

    override fun toString(): String = amount.toString()

    companion object {
        val ZERO = Chips(0)
        val UNIT = Chips(CHIP_UNIT)

        fun of(amount: Long): Chips {
            if (amount < 0) {
                throw InvalidChipsException(HoldemErrorCode.CHIPS_NEGATIVE, "칩은 음수일 수 없습니다: $amount")
            }
            if (amount % CHIP_UNIT != 0L) {
                throw InvalidChipsException(HoldemErrorCode.CHIPS_NOT_UNIT, "칩은 ${CHIP_UNIT} 단위여야 합니다: $amount")
            }
            return Chips(amount)
        }

        /** 영속 복원 전용 — 검증하지 않는다. 사용자 입력에는 [of] 를 써라. */
        fun reconstitute(amount: Long): Chips = Chips(amount)

        fun min(a: Chips, b: Chips): Chips = if (a <= b) a else b
    }
}
