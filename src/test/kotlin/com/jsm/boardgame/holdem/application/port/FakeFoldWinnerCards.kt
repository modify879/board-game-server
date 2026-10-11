package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId

/** 서비스 테스트가 공유하는 인메모리 대역. */
class FakeFoldWinnerCards : FoldWinnerCards {
    val store = mutableMapOf<Long, FoldWinnerCards.FoldWinner>()
    override fun save(tableId: TableId, winner: FoldWinnerCards.FoldWinner) { store[tableId.value] = winner }
    override fun find(tableId: TableId): FoldWinnerCards.FoldWinner? = store[tableId.value]
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}
