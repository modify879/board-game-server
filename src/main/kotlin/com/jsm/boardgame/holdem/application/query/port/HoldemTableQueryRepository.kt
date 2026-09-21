package com.jsm.boardgame.holdem.application.query.port

import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface HoldemTableQueryRepository {
    fun findSeatOf(userId: Long): SeatLocation?
    fun findAllTables(pageable: Pageable): Page<TableSummaryView>
}
