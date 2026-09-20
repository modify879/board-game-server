package com.jsm.boardgame.holdem.domain.model

import kotlin.test.Test
import kotlin.test.assertTrue

class HandRankTest {

    @Test
    fun `카테고리가 다르면 카테고리 순서로 비교한다`() {
        val pair = HandRank(HandCategory.PAIR, listOf(10, 9, 8, 7))
        val straight = HandRank(HandCategory.STRAIGHT, listOf(6))
        assertTrue(straight > pair)
    }

    @Test
    fun `같은 카테고리면 tiebreaker 를 앞에서부터 비교한다`() {
        val highKicker = HandRank(HandCategory.PAIR, listOf(10, 9, 8, 7))
        val lowKicker = HandRank(HandCategory.PAIR, listOf(10, 9, 8, 6))
        assertTrue(highKicker > lowKicker)
    }

    @Test
    fun `카테고리와 tiebreaker 가 모두 같으면 동점이다`() {
        val a = HandRank(HandCategory.TWO_PAIR, listOf(10, 8, 5))
        val b = HandRank(HandCategory.TWO_PAIR, listOf(10, 8, 5))
        assertTrue(a.compareTo(b) == 0)
    }

    @Test
    fun `카테고리 전체 순서는 HIGH_CARD 부터 STRAIGHT_FLUSH 까지다`() {
        val ordered = HandCategory.entries.map { HandRank(it, emptyList()) }
        for (i in 0 until ordered.size - 1) {
            assertTrue(ordered[i] < ordered[i + 1])
        }
    }
}
