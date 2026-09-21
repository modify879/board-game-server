package com.jsm.boardgame.holdem.domain.model

// domain/service 는 이 저장소에서 출력 포트(Shuffler, PasswordHasher) 전용이다.
// 평가기는 인프라 없는 순수 규칙이라 domain/model 에 둔다.
object HandEvaluator {

    /** 5~7장 중 최선의 5장 조합에 대한 [HandRank] 를 돌려준다. 5장 미만·8장 이상·중복 카드는 구현 오류다. */
    fun evaluate(cards: List<Card>): HandRank {
        if (cards.size < 5 || cards.size > 7) error("카드는 5장 이상 7장 이하여야 한다: ${cards.size}장")
        if (cards.distinct().size != cards.size) error("중복된 카드가 있다: $cards")
        return combinationsOfFive(cards).maxOf(::evaluateFive)
    }

    private fun combinationsOfFive(cards: List<Card>): List<List<Card>> {
        if (cards.size == 5) return listOf(cards)
        val result = mutableListOf<List<Card>>()
        val chosen = ArrayDeque<Card>()
        fun combine(start: Int) {
            if (chosen.size == 5) {
                result.add(chosen.toList())
                return
            }
            for (i in start until cards.size) {
                chosen.addLast(cards[i])
                combine(i + 1)
                chosen.removeLast()
            }
        }
        combine(0)
        return result
    }

    private fun evaluateFive(cards: List<Card>): HandRank {
        val ranksDesc = cards.map { it.rank.value }.sortedDescending()
        val isFlush = cards.map { it.suit }.distinct().size == 1
        val distinctRanks = ranksDesc.distinct()
        val straightTop = straightTopOrNull(ranksDesc, distinctRanks)

        // (랭크, 매수) 를 매수 내림차순, 같으면 랭크 내림차순으로 — 페어/트립스/쿼드와 키커 순서를 함께 결정한다.
        val groups = cards.groupingBy { it.rank.value }.eachCount().entries
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
        val groupSizes = groups.map { it.second }

        return when {
            straightTop != null && isFlush -> HandRank(HandCategory.STRAIGHT_FLUSH, listOf(straightTop))
            groupSizes == listOf(4, 1) -> HandRank(HandCategory.FOUR_OF_A_KIND, listOf(groups[0].first, groups[1].first))
            groupSizes == listOf(3, 2) -> HandRank(HandCategory.FULL_HOUSE, listOf(groups[0].first, groups[1].first))
            isFlush -> HandRank(HandCategory.FLUSH, ranksDesc)
            straightTop != null -> HandRank(HandCategory.STRAIGHT, listOf(straightTop))
            groupSizes == listOf(3, 1, 1) ->
                HandRank(HandCategory.THREE_OF_A_KIND, listOf(groups[0].first, groups[1].first, groups[2].first))
            groupSizes == listOf(2, 2, 1) ->
                HandRank(HandCategory.TWO_PAIR, listOf(groups[0].first, groups[1].first, groups[2].first))
            groupSizes == listOf(2, 1, 1, 1) -> HandRank(HandCategory.PAIR, groups.map { it.first })
            else -> HandRank(HandCategory.HIGH_CARD, ranksDesc)
        }
    }

    /** 일반 스트레이트는 최고 랭크를, A-5 휠은 5 를 돌려준다. 스트레이트가 아니면 null. */
    private fun straightTopOrNull(ranksDesc: List<Int>, distinctRanks: List<Int>): Int? {
        if (distinctRanks.size != 5) return null
        if (ranksDesc[0] - ranksDesc[4] == 4) return ranksDesc[0]
        if (ranksDesc == listOf(14, 5, 4, 3, 2)) return 5
        return null
    }
}
