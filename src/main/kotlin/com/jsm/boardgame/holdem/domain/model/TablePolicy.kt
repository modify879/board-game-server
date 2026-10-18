package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.InvalidTablePolicyException

/**
 * 테이블이 만들어질 때 고정되는 블라인드·바이인 범위. 값은 운영 설정에서 오지만 테이블이 자기 상태로 들고 있어
 * 설정을 나중에 바꿔도 이미 열린 테이블의 규칙은 판 도중 바뀌지 않는다. 칩 단위 배수는 [Chips.of] 가 보장한다.
 */
class TablePolicy private constructor(
    val smallBlind: Chips,
    val bigBlind: Chips,
    val minBuyInBigBlinds: Int,
    val maxBuyInBigBlinds: Int,
) {
    val minBuyIn: Chips get() = bigBlind * minBuyInBigBlinds

    /** 칩 추가로 채울 수 있는 스택 상한. 바이인 상한과 같다. */
    val maxStack: Chips get() = bigBlind * maxBuyInBigBlinds

    /** 재착석 의무([obligation])가 하한을 올리고, 상한을 넘으면 상한도 같이 올린다 — 의무가 상한보다 우선한다(하우스 룰 7). */
    fun buyInRange(obligation: Chips): ClosedRange<Chips> = maxOf(minBuyIn, obligation)..maxOf(maxStack, obligation)

    override fun equals(other: Any?): Boolean = other is TablePolicy &&
        smallBlind == other.smallBlind && bigBlind == other.bigBlind &&
        minBuyInBigBlinds == other.minBuyInBigBlinds && maxBuyInBigBlinds == other.maxBuyInBigBlinds

    override fun hashCode(): Int = listOf(smallBlind, bigBlind, minBuyInBigBlinds, maxBuyInBigBlinds).hashCode()

    override fun toString(): String = "TablePolicy($smallBlind/$bigBlind, buyIn=${minBuyInBigBlinds}BB..${maxBuyInBigBlinds}BB)"

    companion object {
        fun of(smallBlind: Long, bigBlind: Long, minBuyInBigBlinds: Int, maxBuyInBigBlinds: Int): TablePolicy {
            if (smallBlind <= 0 || bigBlind <= smallBlind) {
                throw InvalidTablePolicyException("블라인드는 0 < small < big 이어야 합니다: small=$smallBlind, big=$bigBlind")
            }
            if (minBuyInBigBlinds <= 0 || maxBuyInBigBlinds < minBuyInBigBlinds) {
                throw InvalidTablePolicyException("바이인 범위는 0 < min <= max (BB 단위) 여야 합니다: min=$minBuyInBigBlinds, max=$maxBuyInBigBlinds")
            }
            return TablePolicy(Chips.of(smallBlind), Chips.of(bigBlind), minBuyInBigBlinds, maxBuyInBigBlinds)
        }

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(smallBlind: Long, bigBlind: Long, minBuyInBigBlinds: Int, maxBuyInBigBlinds: Int): TablePolicy =
            TablePolicy(Chips.reconstitute(smallBlind), Chips.reconstitute(bigBlind), minBuyInBigBlinds, maxBuyInBigBlinds)
    }
}
