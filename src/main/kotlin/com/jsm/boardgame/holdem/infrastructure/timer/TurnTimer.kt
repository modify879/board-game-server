package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.model.TableId
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
 * 차례 타이머. [HandBroadcastRequested] 는 트랜잭션 커밋 후에 발행되고 테이블·핸드를 그대로
 * 싣고 있어, 이 리스너 하나만 있으면 다음 차례를 다시 조회할 필요가 없다 - 별도 스케줄러
 * 포트 인터페이스는 두지 않는다(구현 하나에 소비자 0인 인터페이스가 된다).
 *
 * 재시작하면 예약이 사라지지만 문제가 되지 않는다. 복구가 끝나면 [HandBroadcastRequested] 가 다시
 * 발행되고 이 타이머가 거기서 새로 예약을 건다 — 계획서의 "복구 후 턴 타이머는 남은 시간을
 * 이어받지 않고 새로 시작한다" 가 그대로 지켜진다.
 */
@Component
class TurnTimer(
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
    private val expireTurnUseCase: ExpireTurnUseCase,
) {
    private class ScheduledExpiry(val future: ScheduledFuture<*>, val token: Long)

    private val scheduled = ConcurrentHashMap<TableId, ScheduledExpiry>()

    // 테이블별 단조 증가 토큰. cancel() 은 최선 노력이라 스레드풀에서 이미 실행이 시작된
    // 작업은 못 멈춘다 - 예약 시점의 토큰을 캡처해두고, 깨어났을 때 현재 토큰과 다르면
    // (그 사이 새 이벤트가 다시 예약했다는 뜻이니) 낡은 실행으로 보고 무시한다.
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    @TransactionalEventListener
    fun onHandBroadcastRequested(event: HandBroadcastRequested) {
        scheduled.remove(event.tableId)?.future?.cancel(false)

        val seatNo = event.hand?.toActSeatNo ?: return
        val token = tokens.computeIfAbsent(event.tableId) { AtomicLong() }.incrementAndGet()
        val future = taskScheduler.schedule(
            { onExpire(event.tableId, seatNo, token) },
            Instant.now(clock).plus(TURN_TIMEOUT),
        )
        scheduled[event.tableId] = ScheduledExpiry(future, token)
    }

    private fun onExpire(tableId: TableId, seatNo: Int, token: Long) {
        if (tokens[tableId]?.get() != token) return
        expireTurnUseCase.expire(ExpireTurnCommand(tableId.value, seatNo))
    }

    companion object {
        // 계획서: 테이블 설정값이 될 자리. 지금은 전역 상수.
        private val TURN_TIMEOUT: Duration = Duration.ofMinutes(3)
    }
}
