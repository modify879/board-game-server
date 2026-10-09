package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.TableExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 닫힘 커밋 후 테이블별 인메모리 상태 중 실행기와 대기열을 치운다(순번은 TableClosedBroadcaster).
 * 타이머 토큰 맵은 건드리지 않는다 — 테이블당 항목 몇 개라 무해하다.
 * 닫힘을 일으킨 작업이 그 테이블 실행기 위에서 돌고 있어도 안전하다 — `remove` 는 진행 중 작업을 끊지 않는다.
 */
@Component
class ClosedTableCleaner(
    private val tableExecutor: TableExecutor,
    private val joinQueue: JoinQueue,
) {
    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        joinQueue.forget(event.tableId)
        tableExecutor.remove(event.tableId)
    }
}
