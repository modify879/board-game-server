package com.jsm.boardgame.holdem.infrastructure.shuffle

import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** 핸드 복원(`Hand.reconstitute`)이 쓰는 셔플러. 고정 덱으로 시작한 핸드의 남은 카드는 고정 순서를 유지한다([QaNewHandShuffler]). */
@Component
@Primary
@Profile("qa")
class QaRestoreShuffler(
    private val decks: QaNewHandShuffler,
    @Qualifier("secureRandomShuffler") private val fallback: Shuffler,
) : Shuffler {
    override fun shuffle(cards: List<Card>): List<Card> = decks.restoreOrder(cards) ?: fallback.shuffle(cards)
}
