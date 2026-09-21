package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.service.Shuffler

class Deck private constructor(private val cards: List<Card>) {

    private var drawn = 0

    val remaining: Int get() = cards.size - drawn

    fun draw(): Card {
        if (remaining == 0) error("덱이 소진되었다")
        return cards[drawn++]
    }

    fun draw(count: Int): List<Card> = List(count) { draw() }

    companion object {
        /** 무늬·끗수 순으로 정렬된 52장. 섞기 전의 기준 덱이다. */
        val FULL: List<Card> = Suit.entries.flatMap { suit -> Rank.entries.map { Card(it, suit) } }

        fun shuffled(shuffler: Shuffler): Deck {
            val cards = shuffler.shuffle(FULL)
            // 셔플러가 카드를 잃거나 복제하면 핸드가 통째로 틀어진다. 클라이언트 오류가 아니라
            // 구현 오류이므로 에러 코드를 주지 않고 그대로 터뜨린다.
            if (cards.size != FULL.size || cards.toSet().size != FULL.size) {
                error("셔플러가 52장의 서로 다른 카드를 돌려주지 않았다: size=${cards.size}")
            }
            return Deck(cards)
        }
    }
}
