package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.TablePolicy
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 진행 중인 핸드에 앉아 있는 동안은 로비를 볼 수 없다 — 자리를 비운 채 다른 테이블을 훑다 차례를 놓치는 것을 막는 안전망이다. */
@Service
@Transactional(readOnly = true)
class HoldemTableQueryService(
    private val holdemTableQuery: HoldemTableQueryRepository,
    private val recentDepartures: RecentDepartures,
) {
    /** 사용자가 앉은 열린 테이블의 id. 앉아 있지 않으면 null. */
    fun findTableIdOf(userId: Long): Long? = holdemTableQuery.findSeatOf(userId)?.tableId

    fun findAll(userId: Long, pageable: Pageable): Page<TableSummaryView> {
        val location = holdemTableQuery.findSeatOf(userId)
        if (location != null && holdemTableQuery.existsHandInProgress(location.tableId)) {
            throw HandInProgressException("진행 중인 핸드가 있어 로비를 볼 수 없습니다: userId=$userId, tableId=${location.tableId}")
        }
        val page = holdemTableQuery.findAllTables(pageable)
        val inProgress = holdemTableQuery.findTableIdsWithHandInProgress(page.content.map { it.tableId })
        val obligations = recentDepartures.obligationsOf(UserId(userId))
        return page.map {
            val obligation = obligations[TableId(it.tableId)]
            val range = TablePolicy.reconstitute(it.smallBlind, it.bigBlind, it.minBuyInBigBlinds, it.maxBuyInBigBlinds)
                .buyInRange(obligation?.amount ?: Chips.ZERO)
            it.copy(
                handInProgress = it.tableId in inProgress,
                minBuyIn = range.start.amount,
                maxBuyIn = range.endInclusive.amount,
                obligationExpiresAt = obligation?.expiresAt,
            )
        }
    }
}
