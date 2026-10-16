package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId

/** 서비스 테스트가 공유하는 인메모리 대역. */
class FakeLastHandResultStore : LastHandResultStore {
    val store = mutableMapOf<Long, LastHandResult>()
    override fun find(tableId: TableId): LastHandResult? = store[tableId.value]
    override fun save(tableId: TableId, result: LastHandResult) { store[tableId.value] = result }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}
