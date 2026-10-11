package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId

/** 서비스 테스트가 공유하는 인메모리 대역. */
class FakeHandHistory : HandHistory {
    val entries = mutableListOf<HandHistoryEntry>()
    val shown = mutableListOf<Pair<TableId, UserId>>()

    override fun record(entry: HandHistoryEntry) { entries += entry }
    override fun markShown(tableId: TableId, userId: UserId) { shown += tableId to userId }
}
