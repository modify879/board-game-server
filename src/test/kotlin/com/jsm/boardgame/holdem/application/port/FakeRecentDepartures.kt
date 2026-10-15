package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import java.time.Instant

/** 서비스 테스트가 공유하는 인메모리 대역. 0 은 의무 해제다(RecentDeparturesAdapter 와 같다). */
class FakeRecentDepartures : RecentDepartures {
    val recorded = mutableListOf<Pair<UserId, Chips>>()
    private val obligations = mutableMapOf<UserId, Departure>()

    override fun record(userId: UserId, amount: Chips) {
        recorded += userId to amount
        if (amount.isZero()) obligations.remove(userId) else obligations[userId] = Departure(amount, Instant.MAX)
    }

    /** 테스트가 만료 시각까지 정해 의무를 심을 때 쓴다. */
    fun put(userId: UserId, departure: Departure) { obligations[userId] = departure }

    override fun obligationOf(userId: UserId): Departure? = obligations[userId]
}
