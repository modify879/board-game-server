package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import java.time.Instant

/** 서비스 테스트가 공유하는 인메모리 대역. 0 은 그 테이블의 의무 해제다(RecentDeparturesAdapter 와 같다). */
class FakeRecentDepartures : RecentDepartures {
    val recorded = mutableListOf<Pair<UserId, Chips>>()
    /** [recorded] 와 같은 순서로 기록된 테이블. */
    val recordedTableIds = mutableListOf<TableId>()
    private val obligations = mutableMapOf<Pair<UserId, TableId>, Departure>()

    override fun record(userId: UserId, tableId: TableId, amount: Chips) {
        recorded += userId to amount
        recordedTableIds += tableId
        val key = userId to tableId
        if (amount.isZero()) obligations.remove(key) else obligations[key] = Departure(amount, Instant.MAX)
    }

    /** 테스트가 만료 시각까지 정해 의무를 심을 때 쓴다. */
    fun put(userId: UserId, tableId: TableId, departure: Departure) { obligations[userId to tableId] = departure }

    override fun obligationOf(userId: UserId, tableId: TableId): Departure? = obligations[userId to tableId]

    override fun obligationsOf(userId: UserId): Map<TableId, Departure> =
        obligations.filterKeys { it.first == userId }.mapKeys { it.key.second }

    override fun clear(userId: UserId) { obligations.keys.removeAll { it.first == userId } }
}
