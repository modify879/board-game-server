package com.jsm.boardgame.holdem.domain.model

/** [eligibleSeats] 는 폴드하지 않은 좌석 중 이 층 금액을 낸 좌석이다. */
data class SidePot(val amount: Chips, val eligibleSeats: Set<Int>)

/** [uncalledSeatNo] 는 아무도 콜하지 않아 그대로 돌려받는 좌석. 없으면 null 이고 [uncalledAmount] 는 [Chips.ZERO] 다. */
data class PotLayout(val pots: List<SidePot>, val uncalledSeatNo: Int?, val uncalledAmount: Chips)

object Pot {

    /**
     * @param contributions 좌석별 핸드 전체 총 투입액
     * @param folded 폴드한 좌석 — 이들의 칩도 팟에는 들어가되 자격에서는 빠진다
     */
    fun layout(contributions: Map<Int, Chips>, folded: Set<Int>): PotLayout {
        val sortedDesc = contributions.values.sortedDescending()
        val top = sortedDesc.getOrElse(0) { Chips.ZERO }
        val second = sortedDesc.getOrElse(1) { Chips.ZERO }
        val topSeats = contributions.filterValues { it == top }.keys

        var uncalledSeatNo: Int? = null
        var uncalledAmount = Chips.ZERO
        val effective = contributions.toMutableMap()
        if (topSeats.size == 1 && top > second) {
            val seatNo = topSeats.first()
            uncalledSeatNo = seatNo
            uncalledAmount = top - second
            effective[seatNo] = second
        }

        val levels = effective.values.filter { it.isPositive() }.distinct().sortedBy { it.amount }
        val rawPots = mutableListOf<SidePot>()
        var prevLevel = Chips.ZERO
        for (level in levels) {
            val contributorCount = effective.count { it.value >= level }
            val amount = (level - prevLevel) * contributorCount
            if (amount.isPositive()) {
                val eligible = effective.filterKeys { it !in folded }.filterValues { it >= level }.keys
                rawPots += SidePot(amount, eligible)
            }
            prevLevel = level
        }

        // 자격자 집합이 같은 인접 층은 하나로 합친다 — 폴드 때문에 쓸데없이 쪼개진 층을 정리한다.
        val merged = mutableListOf<SidePot>()
        for (pot in rawPots) {
            val last = merged.lastOrNull()
            if (last != null && last.eligibleSeats == pot.eligibleSeats) {
                merged[merged.lastIndex] = last.copy(amount = last.amount + pot.amount)
            } else {
                merged += pot
            }
        }

        return PotLayout(merged, uncalledSeatNo, uncalledAmount)
    }

    /**
     * @param ranks 쇼다운에 간 좌석의 핸드 랭크. 폴드한 좌석은 없다
     * @param seatOrderFromButton 버튼 왼쪽부터 시계 방향 좌석 번호 순서 — 나머지 칩 배분 순서
     */
    fun distribute(pots: List<SidePot>, ranks: Map<Int, HandRank>, seatOrderFromButton: List<Int>): Map<Int, Chips> {
        val result = mutableMapOf<Int, Chips>()
        for (pot in pots) {
            val winners = if (pot.eligibleSeats.size == 1) {
                pot.eligibleSeats
            } else {
                val best = pot.eligibleSeats.map { seat ->
                    seat to (ranks[seat] ?: error("쇼다운 자격 좌석의 핸드 랭크가 없다: seat=$seat"))
                }.maxOf { it.second }
                pot.eligibleSeats.filter { ranks[it] == best }.toSet()
            }

            val orderedWinners = seatOrderFromButton.filter { it in winners }
            if (orderedWinners.size != winners.size) {
                error("승자가 좌석 순서에 없다: winners=$winners, seatOrderFromButton=$seatOrderFromButton")
            }
            val (each, remainder) = pot.amount.splitEvenly(orderedWinners.size)
            orderedWinners.forEach { seat -> result[seat] = (result[seat] ?: Chips.ZERO) + each }

            val extraUnits = (remainder.amount / CHIP_UNIT).toInt()
            for (i in 0 until extraUnits) {
                val seat = orderedWinners[i]
                result[seat] = (result[seat] ?: Chips.ZERO) + Chips.UNIT
            }
        }
        return result
    }
}
