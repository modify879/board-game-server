package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 올인 런아웃 타이머. 런아웃 중인 핸드의 [HandBroadcastRequested] 마다 `runout-step-delay` 뒤에 한
 * 스트리트를 진행하도록 건다 — 공개 → 플랍 → 턴 → 리버 → 쇼다운이 각각 한 번의 브로드캐스트다.
 * 진행 결과가 다시 [HandBroadcastRequested] 를 발행하므로 다음 걸음은 거기서 이어서 예약된다.
 *
 * 재시작하면 예약이 사라지지만 복구 후 ResumeHandService 가 [HandBroadcastRequested] 를 다시
 * 발행하고 이 타이머가 거기서 새로 건다 — [TurnTimer] 와 같다.
 */
@Component
class RunoutTimer(
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
    private val advanceRunoutUseCase: AdvanceRunoutUseCase,
    private val tableExecutor: TableExecutor,
    @Value("\${app.holdem.runout-step-delay}") private val stepDelay: Duration,
) {
    private class ScheduledStep(val future: ScheduledFuture<*>, val token: Long)

    private val scheduled = ConcurrentHashMap<TableId, ScheduledStep>()

    // TurnTimer 와 같은 이유 — cancel() 은 최선 노력이라 낡은 실행은 토큰으로 거른다.
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    @TransactionalEventListener
    fun onHandBroadcastRequested(event: HandBroadcastRequested) {
        scheduled.remove(event.tableId)?.future?.cancel(false)

        if (event.hand?.isRunningOut != true) return
        val token = tokens.computeIfAbsent(event.tableId) { AtomicLong() }.incrementAndGet()
        val future = taskScheduler.schedule(
            { onStep(event.tableId, token) },
            Instant.now(clock).plus(stepDelay),
        )
        scheduled[event.tableId] = ScheduledStep(future, token)
    }

    // 낡은 발화는 AdvanceRunoutService 의 isRunningOut 가드가 한 번 더 막는다.
    private fun onStep(tableId: TableId, token: Long) {
        if (tokens[tableId]?.get() != token) return
        tableExecutor.call(tableId) { advanceRunoutUseCase.advance(AdvanceRunoutCommand(tableId.value)) }
    }
}
