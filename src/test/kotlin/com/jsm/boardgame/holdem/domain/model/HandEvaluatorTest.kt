package com.jsm.boardgame.holdem.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HandEvaluatorTest {

    private fun cards(vararg notations: String): List<Card> = notations.map { Card.of(it) }

    @Test
    fun `하이카드를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("As", "Kd", "9h", "5c", "2s"))
        assertEquals(HandRank(HandCategory.HIGH_CARD, listOf(14, 13, 9, 5, 2)), rank)
    }

    @Test
    fun `원페어를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("As", "Ah", "Kd", "9h", "5c"))
        assertEquals(HandRank(HandCategory.PAIR, listOf(14, 13, 9, 5)), rank)
    }

    @Test
    fun `투페어를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("As", "Ah", "Kd", "Kc", "5c"))
        assertEquals(HandRank(HandCategory.TWO_PAIR, listOf(14, 13, 5)), rank)
    }

    @Test
    fun `트립스를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("As", "Ah", "Ad", "9h", "5c"))
        assertEquals(HandRank(HandCategory.THREE_OF_A_KIND, listOf(14, 9, 5)), rank)
    }

    @Test
    fun `스트레이트를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("9h", "8d", "7c", "6s", "5h"))
        assertEquals(HandRank(HandCategory.STRAIGHT, listOf(9)), rank)
    }

    @Test
    fun `A-5 휠 스트레이트는 탑 랭크가 5다`() {
        val rank = HandEvaluator.evaluate(cards("Ah", "2d", "3c", "4s", "5h"))
        assertEquals(HandRank(HandCategory.STRAIGHT, listOf(5)), rank)
    }

    @Test
    fun `A-K-Q-J-T 브로드웨이 스트레이트는 탑 랭크가 14다`() {
        val rank = HandEvaluator.evaluate(cards("Ah", "Kd", "Qc", "Js", "Th"))
        assertEquals(HandRank(HandCategory.STRAIGHT, listOf(14)), rank)
    }

    @Test
    fun `플러시를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("2h", "5h", "9h", "Jh", "Ah"))
        assertEquals(HandRank(HandCategory.FLUSH, listOf(14, 11, 9, 5, 2)), rank)
    }

    @Test
    fun `풀하우스를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("As", "Ah", "Ad", "Kc", "Kh"))
        assertEquals(HandRank(HandCategory.FULL_HOUSE, listOf(14, 13)), rank)
    }

    @Test
    fun `포카드를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("As", "Ah", "Ad", "Ac", "Kh"))
        assertEquals(HandRank(HandCategory.FOUR_OF_A_KIND, listOf(14, 13)), rank)
    }

    @Test
    fun `스트레이트 플러시를 평가한다`() {
        val rank = HandEvaluator.evaluate(cards("9h", "8h", "7h", "6h", "5h"))
        assertEquals(HandRank(HandCategory.STRAIGHT_FLUSH, listOf(9)), rank)
    }

    @Test
    fun `스틸 휠 스트레이트 플러시는 탑 랭크가 5다`() {
        val rank = HandEvaluator.evaluate(cards("Ah", "2h", "3h", "4h", "5h"))
        assertEquals(HandRank(HandCategory.STRAIGHT_FLUSH, listOf(5)), rank)
    }

    @Test
    fun `7장 중 최선의 5장을 고른다`() {
        // 트립스 A 는 4번째 A(Ac) 를 더하면 포카드가 되므로, 7장에서 최선을 골라야 포카드가 나온다
        val rank = HandEvaluator.evaluate(cards("As", "Ah", "Ad", "Ac", "Kh", "5c", "2d"))
        assertEquals(HandRank(HandCategory.FOUR_OF_A_KIND, listOf(14, 13)), rank)
    }

    @Test
    fun `보드 5장이 그대로 최선인 경우 홀카드는 무시된다`() {
        // 보드 자체가 스트레이트 플러시라 무관한 홀카드 2장이 섞여도 결과가 같다
        val board = cards("9h", "8h", "7h", "6h", "5h")
        val rank = HandEvaluator.evaluate(board + cards("2d", "3c"))
        assertEquals(HandRank(HandCategory.STRAIGHT_FLUSH, listOf(9)), rank)
    }

    @Test
    fun `같은 카테고리면 키커로 우열이 갈린다`() {
        val highKicker = HandEvaluator.evaluate(cards("As", "Ah", "Kd", "9h", "5c"))
        val lowKicker = HandEvaluator.evaluate(cards("As", "Ah", "Kd", "9h", "4c"))
        assertTrue(highKicker > lowKicker)
    }

    @Test
    fun `카드 구성이 달라도 완전히 같은 패는 동점이다`() {
        val a = HandEvaluator.evaluate(cards("As", "Ks", "Qs", "Js", "9s"))
        val b = HandEvaluator.evaluate(cards("Ah", "Kh", "Qh", "Jh", "9h"))
        assertTrue(a.compareTo(b) == 0)
    }

    @Test
    fun `플러시가 스트레이트를 이긴다`() {
        val flush = HandEvaluator.evaluate(cards("2h", "5h", "9h", "Jh", "Ah"))
        val straight = HandEvaluator.evaluate(cards("9d", "8c", "7s", "6h", "5d"))
        assertTrue(flush > straight)
    }

    @Test
    fun `카테고리 순서 전체가 지켜진다`() {
        val handsWeakToStrong = listOf(
            HandEvaluator.evaluate(cards("As", "Kd", "9h", "5c", "2s")), // HIGH_CARD
            HandEvaluator.evaluate(cards("As", "Ah", "Kd", "9h", "5c")), // PAIR
            HandEvaluator.evaluate(cards("As", "Ah", "Kd", "Kc", "5c")), // TWO_PAIR
            HandEvaluator.evaluate(cards("As", "Ah", "Ad", "9h", "5c")), // THREE_OF_A_KIND
            HandEvaluator.evaluate(cards("9h", "8d", "7c", "6s", "5h")), // STRAIGHT
            HandEvaluator.evaluate(cards("2h", "5h", "9h", "Jh", "Ah")), // FLUSH
            HandEvaluator.evaluate(cards("As", "Ah", "Ad", "Kc", "Kh")), // FULL_HOUSE
            HandEvaluator.evaluate(cards("As", "Ah", "Ad", "Ac", "Kh")), // FOUR_OF_A_KIND
            HandEvaluator.evaluate(cards("9h", "8h", "7h", "6h", "5h")), // STRAIGHT_FLUSH
        )
        for (i in 0 until handsWeakToStrong.size - 1) {
            assertTrue(handsWeakToStrong[i] < handsWeakToStrong[i + 1])
        }
    }

    @Test
    fun `5장 미만이면 구현 오류로 터진다`() {
        assertFailsWith<IllegalStateException> { HandEvaluator.evaluate(cards("As", "Kd", "9h", "5c")) }
    }

    @Test
    fun `8장 이상이면 구현 오류로 터진다`() {
        assertFailsWith<IllegalStateException> {
            HandEvaluator.evaluate(cards("As", "Kd", "9h", "5c", "2s", "3h", "4d", "6c"))
        }
    }

    @Test
    fun `중복 카드는 구현 오류로 터진다`() {
        assertFailsWith<IllegalStateException> { HandEvaluator.evaluate(cards("As", "As", "9h", "5c", "2s")) }
    }
}
