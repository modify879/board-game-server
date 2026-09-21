package com.jsm.boardgame.holdem.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PotTest {

    private fun c(amount: Long): Chips = Chips.of(amount)

    private fun rank(category: HandCategory, vararg tiebreakers: Int): HandRank =
        HandRank(category, tiebreakers.toList())

    private fun PotLayout.totalPotAmount(): Chips = pots.fold(Chips.ZERO) { acc, pot -> acc + pot.amount }

    private fun Map<Int, Chips>.total(): Chips = values.fold(Chips.ZERO) { acc, v -> acc + v }

    @Test
    fun `모두 같은 금액을 낸 단일 메인팟`() {
        val contributions = mapOf(1 to c(1000), 2 to c(1000), 3 to c(1000))
        val layout = Pot.layout(contributions, folded = emptySet())

        assertEquals(1, layout.pots.size)
        assertEquals(c(3000), layout.pots[0].amount)
        assertEquals(setOf(1, 2, 3), layout.pots[0].eligibleSeats)
        assertNull(layout.uncalledSeatNo)
    }

    @Test
    fun `한 명 올인으로 사이드팟이 2층으로 쌓인다`() {
        val contributions = mapOf(1 to c(500), 2 to c(1000), 3 to c(1000))
        val layout = Pot.layout(contributions, folded = emptySet())

        assertEquals(2, layout.pots.size)
        assertEquals(c(1500), layout.pots[0].amount)
        assertEquals(setOf(1, 2, 3), layout.pots[0].eligibleSeats)
        assertEquals(c(1000), layout.pots[1].amount)
        assertEquals(setOf(2, 3), layout.pots[1].eligibleSeats)
        assertEquals(c(2500), layout.totalPotAmount())
    }

    @Test
    fun `두 명 올인으로 사이드팟이 3층으로 쌓인다`() {
        val contributions = mapOf(1 to c(200), 2 to c(500), 3 to c(800), 4 to c(800))
        val layout = Pot.layout(contributions, folded = emptySet())

        assertEquals(3, layout.pots.size)
        assertEquals(c(800), layout.pots[0].amount)
        assertEquals(setOf(1, 2, 3, 4), layout.pots[0].eligibleSeats)
        assertEquals(c(900), layout.pots[1].amount)
        assertEquals(setOf(2, 3, 4), layout.pots[1].eligibleSeats)
        assertEquals(c(600), layout.pots[2].amount)
        assertEquals(setOf(3, 4), layout.pots[2].eligibleSeats)
        assertNull(layout.uncalledSeatNo)
        assertEquals(c(2300), layout.totalPotAmount())
    }

    @Test
    fun `폴드한 좌석의 칩도 메인팟 금액에는 남지만 자격은 없다`() {
        val contributions = mapOf(1 to c(500), 2 to c(500), 3 to c(500))
        val layout = Pot.layout(contributions, folded = setOf(3))

        assertEquals(1, layout.pots.size)
        assertEquals(c(1500), layout.pots[0].amount)
        assertEquals(setOf(1, 2), layout.pots[0].eligibleSeats)
    }

    @Test
    fun `아무도 콜하지 않은 초과분은 반환되고 팟은 2위 수준으로 쌓인다`() {
        val contributions = mapOf(1 to c(1000), 2 to c(300))
        val layout = Pot.layout(contributions, folded = emptySet())

        assertEquals(1, layout.uncalledSeatNo)
        assertEquals(c(700), layout.uncalledAmount)
        assertEquals(1, layout.pots.size)
        assertEquals(c(600), layout.pots[0].amount)
        assertEquals(setOf(1, 2), layout.pots[0].eligibleSeats)
        // 팟 총합 + 언콜드 반환액 == 총 투입액
        assertEquals(contributions.total(), layout.totalPotAmount() + layout.uncalledAmount)
    }

    @Test
    fun `자격자 집합이 같은 인접 층은 하나로 합쳐진다`() {
        // 좌석 1 은 300 에서 폴드해 그 지점에만 층 경계를 만들지만, 활성 자격자 집합은 2,3 으로 두 층 모두 동일하다
        val contributions = mapOf(1 to c(300), 2 to c(900), 3 to c(900))
        val layout = Pot.layout(contributions, folded = setOf(1))

        assertEquals(1, layout.pots.size)
        assertEquals(c(2100), layout.pots[0].amount)
        assertEquals(setOf(2, 3), layout.pots[0].eligibleSeats)
    }

    @Test
    fun `2500 을 둘이 나누면 1200씩과 나머지 100은 버튼 왼쪽 승자에게 간다`() {
        val pot = SidePot(c(2500), setOf(1, 2))
        val ranks = mapOf(1 to rank(HandCategory.PAIR, 10), 2 to rank(HandCategory.PAIR, 10))
        val result = Pot.distribute(listOf(pot), ranks, seatOrderFromButton = listOf(2, 1, 3))

        assertEquals(c(1300), result[2])
        assertEquals(c(1200), result[1])
        assertEquals(c(2500), result.total())
    }

    @Test
    fun `3분할 나머지는 버튼 왼쪽 순서대로 한 단위씩 돌아간다`() {
        val pot = SidePot(c(1100), setOf(1, 2, 3))
        val ranks = mapOf(
            1 to rank(HandCategory.STRAIGHT, 8),
            2 to rank(HandCategory.STRAIGHT, 8),
            3 to rank(HandCategory.STRAIGHT, 8),
        )
        val result = Pot.distribute(listOf(pot), ranks, seatOrderFromButton = listOf(3, 1, 2))

        assertEquals(c(400), result[3])
        assertEquals(c(400), result[1])
        assertEquals(c(300), result[2])
        assertEquals(c(1100), result.total())
    }

    @Test
    fun `자격자가 한 명이면 쇼다운 없이 전액을 받는다`() {
        val pot = SidePot(c(500), setOf(5))
        val result = Pot.distribute(listOf(pot), ranks = emptyMap(), seatOrderFromButton = listOf(5))

        assertEquals(c(500), result[5])
    }

    @Test
    fun `사이드팟 승자와 메인팟 승자가 다르고 층별 총합이 보존된다`() {
        val contributions = mapOf(1 to c(200), 2 to c(500), 3 to c(800), 4 to c(800))
        val layout = Pot.layout(contributions, folded = emptySet())

        val ranks = mapOf(
            1 to rank(HandCategory.FULL_HOUSE, 10, 5),
            2 to rank(HandCategory.FLUSH, 12),
            3 to rank(HandCategory.THREE_OF_A_KIND, 7),
            4 to rank(HandCategory.STRAIGHT, 9),
        )
        val result = Pot.distribute(layout.pots, ranks, seatOrderFromButton = listOf(1, 2, 3, 4))

        assertEquals(c(800), result[1]) // 메인팟 승자
        assertEquals(c(900), result[2]) // 2번째 사이드팟 승자
        assertEquals(c(600), result[4]) // 3번째 사이드팟 승자
        assertNull(result[3])
        assertEquals(layout.totalPotAmount(), result.total())
    }
}
