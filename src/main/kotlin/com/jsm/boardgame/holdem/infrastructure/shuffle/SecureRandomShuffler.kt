package com.jsm.boardgame.holdem.infrastructure.shuffle

import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.stereotype.Component
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

@Component
class SecureRandomShuffler : Shuffler {
    private val random = SecureRandom().asKotlinRandom()

    override fun shuffle(cards: List<Card>): List<Card> = cards.shuffled(random)
}
