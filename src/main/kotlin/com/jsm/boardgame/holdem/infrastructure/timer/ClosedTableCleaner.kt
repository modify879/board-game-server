package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.infrastructure.recovery.HandRecovery
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 닫힘 커밋 후 테이블별 인메모리 상태 중 실행기·대기열·타이머 예약과 토큰을 치운다(순번은 TableClosedBroadcaster).
 * 테이블이 계속 만들어지고 닫히므로 치우지 않은 항목은 끝없이 쌓인다.
 * 닫힘을 일으킨 작업이 그 테이블 실행기 위에서 돌고 있어도 안전하다 — `remove` 는 진행 중 작업을 끊지 않는다.
 */
@Component
class ClosedTableCleaner(
    private val tableExecutor: TableExecutor,
    private val joinQueue: JoinQueue,
    private val turnTimer: TurnTimer,
    private val nextHandTimer: NextHandTimer,
    private val runoutTimer: RunoutTimer,
    private val connectionTimer: ConnectionTimer,
    private val handRecovery: HandRecovery,
) {
    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        joinQueue.forget(event.tableId)
        turnTimer.forget(event.tableId)
        nextHandTimer.forget(event.tableId)
        runoutTimer.forget(event.tableId)
        connectionTimer.forget(event.tableId)
        handRecovery.forget(event.tableId)
        tableExecutor.remove(event.tableId)
    }
}
