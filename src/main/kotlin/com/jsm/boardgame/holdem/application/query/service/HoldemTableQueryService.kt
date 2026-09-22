package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 진행 중인 핸드에 앉아 있는 동안은 로비를 볼 수 없다 — 계획서의 안전망(자리를 비운 채 다른 테이블을 훑지 못하게). */
@Service
@Transactional(readOnly = true)
class HoldemTableQueryService(
    private val holdemTableQuery: HoldemTableQueryRepository,
    private val handStore: HandStore,
) {
    fun findAll(userId: Long, pageable: Pageable): Page<TableSummaryView> {
        val location = holdemTableQuery.findSeatOf(userId)
        if (location != null && handStore.find(TableId(location.tableId)) != null) {
            throw HandInProgressException("진행 중인 핸드가 있어 로비를 볼 수 없습니다: userId=$userId, tableId=${location.tableId}")
        }
        return holdemTableQuery.findAllTables(pageable)
    }
}
