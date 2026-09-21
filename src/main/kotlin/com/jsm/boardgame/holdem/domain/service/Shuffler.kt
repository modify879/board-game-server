package com.jsm.boardgame.holdem.domain.service

import com.jsm.boardgame.holdem.domain.model.Card

/**
 * 무작위성은 주입받는다(규칙 5). 도메인이 직접 난수를 부르면 패를 고정할 수 없어
 * 핸드 평가·사이드팟 규칙을 테스트할 수 없다.
 */
fun interface Shuffler {
    fun shuffle(cards: List<Card>): List<Card>
}
