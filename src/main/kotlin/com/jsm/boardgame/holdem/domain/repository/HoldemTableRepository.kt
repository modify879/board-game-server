package com.jsm.boardgame.holdem.domain.repository

import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId

interface HoldemTableRepository {
    fun findById(id: TableId): HoldemTable?

    /** 주어진 사용자가 현재 앉아 있는 테이블(없으면 null). */
    fun findByUserId(userId: Long): HoldemTable?

    fun save(table: HoldemTable): HoldemTable
}
