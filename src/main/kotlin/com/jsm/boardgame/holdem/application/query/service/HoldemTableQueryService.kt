package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.exception.HandInProgressException
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
) {
    /** 사용자가 앉은 열린 테이블의 id. 앉아 있지 않으면 null. */
    fun findTableIdOf(userId: Long): Long? = holdemTableQuery.findSeatOf(userId)?.tableId

    fun findAll(userId: Long, pageable: Pageable): Page<TableSummaryView> {
        val location = holdemTableQuery.findSeatOf(userId)
        if (location != null && holdemTableQuery.existsHandInProgress(location.tableId)) {
            throw HandInProgressException("진행 중인 핸드가 있어 로비를 볼 수 없습니다: userId=$userId, tableId=${location.tableId}")
        }
        // ponytail: 페이지 크기만큼 존재 조회를 반복한다(쿼리 N번). 페이지가 커지면 일괄 조회 메서드를 포트에 둔다.
        return holdemTableQuery.findAllTables(pageable).map {
            it.copy(handInProgress = holdemTableQuery.existsHandInProgress(it.tableId))
        }
    }
}
