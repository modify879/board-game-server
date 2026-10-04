package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId

/** 테이블당 직전 핸드의 공개 결과 1건. 히스토리가 아니다 — 저장하면 덮어쓰고, 새 핸드가 시작되면 지운다. */
interface LastHandResultStore {
    fun find(tableId: TableId): LastHandResult?
    fun save(tableId: TableId, result: LastHandResult)
    fun remove(tableId: TableId)
}
