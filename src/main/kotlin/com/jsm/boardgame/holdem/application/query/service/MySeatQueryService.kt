package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.MySeatView
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 이 조회는 언제나 허용된다. StartHand/PlayAction 이 HAND_IN_PROGRESS 등으로 409 를 던졌을 때
 * 클라이언트가 "지금 내가 앉은 테이블이 어디고 핸드가 진행 중인지" 알 방법이 있어야 한다.
 * 오류 응답의 detail 에 tableId 를 실어 우회하지 않고(규칙 8, detail 은 비어 있는 게 정상),
 * 별도 조회로 뺀다.
 */
@Service
@Transactional(readOnly = true)
class MySeatQueryService(
    private val holdemTableQuery: HoldemTableQueryRepository,
    private val handStore: HandStore,
) {
    fun findMine(userId: Long): MySeatView? {
        val location = holdemTableQuery.findSeatOf(userId) ?: return null
        val handInProgress = handStore.find(TableId(location.tableId)) != null
        return MySeatView(location.tableId, location.seatNo, handInProgress)
    }
}
