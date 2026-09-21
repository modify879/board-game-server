package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// 테스트용 Shuffler 구현들 — 테스트별 접두를 붙인다
private class DeckFakeIdentityShuffler : Shuffler {
    override fun shuffle(cards: List<Card>): List<Card> = cards
}

private class DeckFakeReverseShuffler : Shuffler {
    override fun shuffle(cards: List<Card>): List<Card> = cards.reversed()
}

private class DeckFakeLosingShuffler : Shuffler {
    override fun shuffle(cards: List<Card>): List<Card> = cards.dropLast(1)
}

private class DeckFakeDuplicatingShuffler : Shuffler {
    override fun shuffle(cards: List<Card>): List<Card> = cards + cards.first()
}

class DeckTest {

    @Test
    fun `FULL 덱은 52장이다`() {
        assertEquals(52, Deck.FULL.size)
    }

    @Test
    fun `FULL 덱의 모든 카드는 서로 다르다`() {
        val uniqueCards = Deck.FULL.toSet()
        assertEquals(52, uniqueCards.size)
    }

    @Test
    fun `FULL 덱은 무늬별로 13장씩 있다`() {
        for (suit in Suit.entries) {
            val count = Deck.FULL.count { it.suit == suit }
            assertEquals(13, count)
        }
    }

    @Test
    fun `항등 셔플러로 뽑는 순서는 셔플러가 준 순서와 같다`() {
        val deck = Deck.shuffled(DeckFakeIdentityShuffler())
        val cards = deck.draw(5)
        assertEquals(Deck.FULL.take(5), cards)
    }

    @Test
    fun `뒤집기 셔플러로 뽑는 순서는 역순이다`() {
        val deck = Deck.shuffled(DeckFakeReverseShuffler())
        val cards = deck.draw(5)
        assertEquals(Deck.FULL.reversed().take(5), cards)
    }

    @Test
    fun `draw 는 요청한 개수만큼 카드를 돌려준다`() {
        val deck = Deck.shuffled(DeckFakeIdentityShuffler())
        val cards = deck.draw(7)
        assertEquals(7, cards.size)
    }

    @Test
    fun `remaining 은 남은 카드 개수를 돌려준다`() {
        val deck = Deck.shuffled(DeckFakeIdentityShuffler())
        assertEquals(52, deck.remaining)
        deck.draw(5)
        assertEquals(47, deck.remaining)
        deck.draw(47)
        assertEquals(0, deck.remaining)
    }

    @Test
    fun `52장째를 뽑은 후 남은 카드는 0이다`() {
        val deck = Deck.shuffled(DeckFakeIdentityShuffler())
        deck.draw(52)
        assertEquals(0, deck.remaining)
    }

    @Test
    fun `빈 덱에서 뽑으면 에러를 던진다`() {
        val deck = Deck.shuffled(DeckFakeIdentityShuffler())
        deck.draw(52)
        assertFailsWith<IllegalStateException> { deck.draw() }
    }

    @Test
    fun `53장을 한 번에 뽑으려 하면 에러를 던진다`() {
        val deck = Deck.shuffled(DeckFakeIdentityShuffler())
        assertFailsWith<IllegalStateException> { deck.draw(53) }
    }

    @Test
    fun `셔플러가 카드를 잃으면 에러를 던진다`() {
        assertFailsWith<IllegalStateException> {
            Deck.shuffled(DeckFakeLosingShuffler())
        }
    }

    @Test
    fun `셔플러가 카드를 복제하면 에러를 던진다`() {
        assertFailsWith<IllegalStateException> {
            Deck.shuffled(DeckFakeDuplicatingShuffler())
        }
    }

    @Test
    fun `draw 는 순서대로 카드를 소비한다`() {
        val deck = Deck.shuffled(DeckFakeIdentityShuffler())
        val first = deck.draw()
        val second = deck.draw()
        assertEquals(Deck.FULL[0], first)
        assertEquals(Deck.FULL[1], second)
        assertEquals(50, deck.remaining)
    }
}
