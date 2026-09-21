package com.jsm.boardgame.holdem.domain.model

enum class Suit(val symbol: Char) {
    SPADE('s'), HEART('h'), DIAMOND('d'), CLUB('c'),
    ;

    companion object {
        fun of(symbol: Char): Suit = entries.firstOrNull { it.symbol == symbol }
            ?: error("알 수 없는 무늬: $symbol")
    }
}

/** [value] 는 비교용 숫자다. 에이스는 14 이고, A-5 스트레이트에서만 1 로 취급된다. */
enum class Rank(val value: Int, val symbol: Char) {
    TWO(2, '2'), THREE(3, '3'), FOUR(4, '4'), FIVE(5, '5'), SIX(6, '6'), SEVEN(7, '7'),
    EIGHT(8, '8'), NINE(9, '9'), TEN(10, 'T'), JACK(11, 'J'), QUEEN(12, 'Q'), KING(13, 'K'), ACE(14, 'A'),
    ;

    companion object {
        fun of(symbol: Char): Rank = entries.firstOrNull { it.symbol == symbol }
            ?: error("알 수 없는 끗수: $symbol")
    }
}

data class Card(val rank: Rank, val suit: Suit) {
    override fun toString(): String = "${rank.symbol}${suit.symbol}"

    companion object {
        /** `"As"`, `"Th"` 같은 표기로 카드를 만든다. 로그와 고정 덱을 사람이 읽을 수 있게 한다. */
        fun of(notation: String): Card {
            if (notation.length != 2) error("카드 표기는 두 글자여야 한다: $notation")
            return Card(Rank.of(notation[0]), Suit.of(notation[1]))
        }
    }
}
