package com.jsm.boardgame.holdem.domain.model

enum class HandCategory {
    HIGH_CARD, PAIR, TWO_PAIR, THREE_OF_A_KIND, STRAIGHT, FLUSH, FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH,
}

/**
 * [tiebreakers] 는 항상 내림차순, [Rank.value] 숫자다. 같은 [category] 끼리는 길이가 항상 같아
 * 원소별 비교가 안전하다.
 */
data class HandRank(val category: HandCategory, val tiebreakers: List<Int>) : Comparable<HandRank> {
    override fun compareTo(other: HandRank): Int {
        val byCategory = category.ordinal.compareTo(other.category.ordinal)
        if (byCategory != 0) return byCategory
        for (i in tiebreakers.indices) {
            val byTiebreaker = tiebreakers[i].compareTo(other.tiebreakers[i])
            if (byTiebreaker != 0) return byTiebreaker
        }
        return 0
    }
}
