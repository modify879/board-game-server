package com.jsm.boardgame.holdem.infrastructure.shuffle

import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Deck
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class QaNewHandShufflerTest {

    private val identity = Shuffler { it }
    private val tableId = TableId(1)

    private fun cards(vararg notations: String) = notations.map(Card::of)

    @Test
    fun `딜 순서는 버튼 다음 참가 좌석부터 순환하고 버튼이 마지막이다`() {
        assertEquals(listOf(4, 7, 2), QaNewHandShuffler.dealOrder(listOf(2, 4, 7), 2))
        assertEquals(listOf(7, 2, 4), QaNewHandShuffler.dealOrder(listOf(2, 4, 7), 4))
        assertEquals(listOf(2, 4, 7), QaNewHandShuffler.dealOrder(listOf(2, 4, 7), 7))
        // 헤즈업: BB 가 먼저, 버튼이 마지막
        assertEquals(listOf(5, 3), QaNewHandShuffler.dealOrder(listOf(3, 5), 3))
        assertEquals(listOf(3, 5), QaNewHandShuffler.dealOrder(listOf(3, 5), 5))
    }

    @Test
    fun `지정한 홀카드와 보드가 딜 순서 규칙의 덱 위치에 놓이고 나머지는 중복 없이 채워진다`() {
        val shuffler = QaNewHandShuffler(identity)
        // 좌석 2,4,7 / 버튼 4 → 딜 순서 7,2,4 (n=3)
        shuffler.reserve(
            tableId,
            DeckSpec(mapOf(2 to cards("As", "Ah"), 4 to cards("Kc")), cards("Ad", "7c", "2h", "9s")),
        )
        val deck = shuffler.shufflerFor(tableId, listOf(2, 4, 7), 4).shuffle(Deck.FULL)

        assertEquals(52, deck.toSet().size)
        assertEquals(Card.of("As"), deck[1]) // 좌석2, 첫 장: 0*3 + index 1
        assertEquals(Card.of("Ah"), deck[4]) // 좌석2, 둘째 장: 1*3 + 1
        assertEquals(Card.of("Kc"), deck[2]) // 좌석4: index 2
        assertEquals(cards("Ad", "7c", "2h", "9s"), deck.subList(6, 10)) // 보드는 2n = 6 부터
    }

    @Test
    fun `참가자가 아닌 좌석의 지정은 무시되고 그 카드는 무작위 풀로 돌아간다`() {
        val shuffler = QaNewHandShuffler(identity)
        shuffler.reserve(tableId, DeckSpec(mapOf(9 to cards("As", "Ah")), emptyList()))
        val deck = shuffler.shufflerFor(tableId, listOf(1, 2), 1).shuffle(Deck.FULL)

        assertEquals(Deck.FULL, deck) // 항등 폴백 + 아무것도 고정되지 않음
    }

    @Test
    fun `복원 때 남은 카드는 고정 덱의 꼬리 순서로 돌아오고 다음 핸드가 시작되면 잊는다`() {
        val shuffler = QaNewHandShuffler(identity)
        shuffler.reserve(tableId, DeckSpec(emptyMap(), cards("Ad", "7c", "2h", "9s", "3d")))
        val deck = shuffler.shufflerFor(tableId, listOf(1, 2), 1).shuffle(Deck.FULL)

        val dealt = deck.take(7) // 홀카드 4 + 플랍 3
        val remaining = (Deck.FULL - dealt.toSet()).reversed()
        assertEquals(deck.drop(7), shuffler.restoreOrder(remaining))
        assertEquals(null, shuffler.restoreOrder(Deck.FULL.take(10)))

        shuffler.shufflerFor(tableId, listOf(1, 2), 1)
        assertEquals(null, shuffler.restoreOrder(remaining))
    }

    @Test
    fun `사양은 1회용이고 없으면 기본 셔플러를 그대로 돌려준다`() {
        val shuffler = QaNewHandShuffler(identity)
        shuffler.reserve(tableId, DeckSpec(emptyMap(), cards("Ad")))

        assertNotSame(identity, shuffler.shufflerFor(tableId, listOf(1, 2), 1))
        assertSame(identity, shuffler.shufflerFor(tableId, listOf(1, 2), 1))
    }

    @Test
    fun `잘못된 사양은 만들 때 거부한다`() {
        assertFailsWith<IllegalArgumentException> { DeckSpec(mapOf(1 to cards("As", "Ah")), cards("As")) } // 중복
        assertFailsWith<IllegalArgumentException> { DeckSpec(mapOf(0 to cards("As")), emptyList()) } // 좌석 범위
        assertFailsWith<IllegalArgumentException> { DeckSpec(mapOf(10 to cards("As")), emptyList()) }
        assertFailsWith<IllegalArgumentException> { DeckSpec(mapOf(1 to cards("As", "Ah", "Ad")), emptyList()) } // 장수
        assertFailsWith<IllegalArgumentException> { DeckSpec(emptyMap(), cards("2c", "3c", "4c", "5c", "6c", "7c")) }
    }
}
