package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 다음 핸드 자동 시작 타이머. [TurnTimer] 와 예약/토큰 패턴이 같다 — 테이블별 단조 증가 토큰으로
 * cancel() 이 못 막은 stale 실행을 걸러낸다.
 *
 * 시각은 [HandBroadcastRequested] 에 실려오는 table.nextHandAt(DB 값)에서 그대로 읽는다 — 이
 * 컴포넌트 자신은 시각을 계산하지 않는다. 재시작하면 이 인메모리 예약은 사라지지만, HandRecovery
 * 가 부팅 시 DB 의 nextHandAt 을 보고 [scheduleAt] 으로 다시 건다.
 */
@Component
class NextHandTimer(
    private val taskScheduler: TaskScheduler,
    private val startScheduledHandUseCase: StartScheduledHandUseCase,
) {
    private class ScheduledStart(val future: ScheduledFuture<*>, val token: Long)

    private val scheduled = ConcurrentHashMap<TableId, ScheduledStart>()
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    @TransactionalEventListener
    fun onHandBroadcastRequested(event: HandBroadcastRequested) {
        scheduleAt(event.tableId, event.table.nextHandAt)
    }

    /** [com.jsm.boardgame.holdem.infrastructure.recovery.HandRecovery] 가 재시작 시 재무장할 때도 같은 경로를 쓴다. [at] 이 null 이면 예약을 취소만 한다. */
    fun scheduleAt(tableId: TableId, at: Instant?) {
        scheduled.remove(tableId)?.future?.cancel(false)
        if (at == null) return

        val token = tokens.computeIfAbsent(tableId) { AtomicLong() }.incrementAndGet()
        val future = taskScheduler.schedule({ onFire(tableId, token) }, at)
        scheduled[tableId] = ScheduledStart(future, token)
    }

    private fun onFire(tableId: TableId, token: Long) {
        if (tokens[tableId]?.get() != token) return
        try {
            startScheduledHandUseCase.start(StartScheduledHandCommand(tableId.value))
        } catch (e: ConcurrentTableUpdateException) {
            log.info("자동 시작이 다른 트랜잭션과 경합해 무시했습니다: tableId={}", tableId.value)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NextHandTimer::class.java)
    }
}
