package com.jsm.boardgame.holdem.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CardTest {

    @Test
    fun `of 는 표기로부터 카드를 파싱한다`() {
        val card = Card.of("As")
        assertEquals(Rank.ACE, card.rank)
        assertEquals(Suit.SPADE, card.suit)
    }

    @Test
    fun `of 는 Th 표기를 파싱한다`() {
        val card = Card.of("Th")
        assertEquals(Rank.TEN, card.rank)
        assertEquals(Suit.HEART, card.suit)
    }

    @Test
    fun `toString 은 두 글자 표기를 돌려준다`() {
        assertEquals("As", Card.of("As").toString())
        assertEquals("Kd", Card.of("Kd").toString())
        assertEquals("2c", Card.of("2c").toString())
    }

    @Test
    fun `toString 과 of 는 왕복한다`() {
        val original = Card.of("Jh")
        val roundtrip = Card.of(original.toString())
        assertEquals(original, roundtrip)
    }

    @Test
    fun `한 글자 표기는 에러를 던진다`() {
        assertFailsWith<IllegalStateException> { Card.of("A") }
    }

    @Test
    fun `세 글자 표기는 에러를 던진다`() {
        assertFailsWith<IllegalStateException> { Card.of("Ass") }
    }

    @Test
    fun `알 수 없는 끗수는 에러를 던진다`() {
        assertFailsWith<IllegalStateException> { Card.of("Xs") }
    }

    @Test
    fun `알 수 없는 무늬는 에러를 던진다`() {
        assertFailsWith<IllegalStateException> { Card.of("Ax") }
    }

    @Test
    fun `에이스의 끗수 값은 14다`() {
        assertEquals(14, Rank.ACE.value)
    }

    @Test
    fun `킹의 끗수 값은 13이다`() {
        assertEquals(13, Rank.KING.value)
    }

    @Test
    fun `2의 끗수 값은 2다`() {
        assertEquals(2, Rank.TWO.value)
    }

    @Test
    fun `모든 끗수의 값은 2 이상 14 이하다`() {
        for (rank in Rank.entries) {
            assert(rank.value in 2..14)
        }
    }
}
