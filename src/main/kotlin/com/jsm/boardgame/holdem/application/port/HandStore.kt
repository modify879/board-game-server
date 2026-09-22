package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId

/** 진행 중인 핸드 보관소. */
interface HandStore {
    fun find(tableId: TableId): Hand?
    fun save(tableId: TableId, hand: Hand)
    fun remove(tableId: TableId)
}
