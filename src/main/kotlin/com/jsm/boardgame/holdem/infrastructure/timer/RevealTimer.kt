package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.ExpireRevealCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireRevealUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 쇼다운 공개 선택 제한시간 타이머. [TurnTimer] 와 예약/토큰 패턴이 같다.
 *
 * `event.hand == null` 이면 무시한다 — 착석·기립 브로드캐스트([HandStarter.rescheduleOnEntry] 등)가
 * 열려 있는 선택 창을 꺼버리면 안 된다. hand 가 있으면 기존 예약을 취소하고, 그 핸드가 끝났고
 * 아직 결정하지 않은 좌석이 있으면 [com.jsm.boardgame.holdem.domain.model.Hand.revealDeadline] 에
 * [ExpireRevealUseCase] 를 새로 예약한다.
 */
@Component
class RevealTimer(
    private val taskScheduler: TaskScheduler,
    private val expireRevealUseCase: ExpireRevealUseCase,
) {
    private class ScheduledExpiry(val future: ScheduledFuture<*>, val token: Long)

    private val scheduled = ConcurrentHashMap<TableId, ScheduledExpiry>()
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    @TransactionalEventListener
    fun onHandBroadcastRequested(event: HandBroadcastRequested) {
        val hand = event.hand ?: return
        scheduled.remove(event.tableId)?.future?.cancel(false)

        val deadline = hand.revealDeadline
        if (!hand.isFinished || hand.awaitingRevealSeatNos.isEmpty() || deadline == null) return

        val token = tokens.computeIfAbsent(event.tableId) { AtomicLong() }.incrementAndGet()
        val future = taskScheduler.schedule({ onExpire(event.tableId, token) }, deadline)
        scheduled[event.tableId] = ScheduledExpiry(future, token)
    }

    private fun onExpire(tableId: TableId, token: Long) {
        if (tokens[tableId]?.get() != token) return
        try {
            expireRevealUseCase.expire(ExpireRevealCommand(tableId.value))
        } catch (e: ConcurrentTableUpdateException) {
            // 타이머가 경합에서 진 쪽이다 - 이긴 쪽이 이미 커밋되며 HandBroadcastRequested 를 발행했고,
            // 그게 이 타이머를 새로 예약했거나 취소했다. 낡은 만료를 버려도 잃는 것이 없다.
            log.info("공개 선택 만료가 다른 트랜잭션과 경합해 무시했습니다: tableId={}", tableId.value)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RevealTimer::class.java)
    }
}
