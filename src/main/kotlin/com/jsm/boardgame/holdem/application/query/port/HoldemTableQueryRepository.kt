package com.jsm.boardgame.holdem.application.query.port

import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface HoldemTableQueryRepository {
    fun findSeatOf(userId: Long): SeatLocation?
    fun findAllTables(pageable: Pageable): Page<TableSummaryView>

    /** 열려 있고 앉은 사용자가 없는 테이블 id. 방치된 테이블을 닫을 후보를 찾는 용도. */
    fun findEmptyTableIds(): List<Long>
}
