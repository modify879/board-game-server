package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId

/** 진행 중인 핸드 보관소. */
interface HandStore {
    fun find(tableId: TableId): Hand?
    fun save(tableId: TableId, hand: Hand)
    fun remove(tableId: TableId)

    /** 진행 중인 핸드가 있는 모든 테이블 id. 부팅 시 재시작 복구가 전부 훑는 용도로만 쓴다. */
    fun findAllInProgress(): List<TableId>
}
