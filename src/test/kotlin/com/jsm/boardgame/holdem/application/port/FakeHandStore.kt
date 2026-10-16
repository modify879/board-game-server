package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId

/** 서비스 테스트가 공유하는 인메모리 대역. */
class FakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    fun put(tableId: Long, hand: Hand) { stored[tableId] = hand }
    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}
