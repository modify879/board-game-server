package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.TurnCountdown
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
    private val tableExecutor: TableExecutor,
    private val cancelHandUseCase: CancelHandUseCase,
) : TurnCountdown {
    private class ScheduledExpiry(val future: ScheduledFuture<*>, val token: Long)

    private val scheduled = ConcurrentHashMap<TableId, ScheduledExpiry>()

    // 테이블별 단조 증가 토큰. cancel() 은 최선 노력이라 스레드풀에서 이미 실행이 시작된
    // 작업은 못 멈춘다 - 예약 시점의 토큰을 캡처해두고, 깨어났을 때 현재 토큰과 다르면
    // (그 사이 새 이벤트가 다시 예약했다는 뜻이니) 낡은 실행으로 보고 무시한다.
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    // AFTER_COMMIT 리스너는 afterCompletion 에서 돌아 HandBroadcaster 의 afterCommit 보다 늦다 - 그러면
    // 브로드캐스트가 이전 예약을 읽는다(turnRemainingMs). 그래서 BEFORE_COMMIT 에서 동기화를 등록하고,
    // 가장 앞선 order 의 afterCommit 에서 재예약한다(getSynchronizations 가 order 로 정렬한다).
    // 롤백되면 afterCommit 이 안 돌아 예약도 남지 않는다.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun onHandBroadcastRequested(event: HandBroadcastRequested) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
            override fun afterCommit() {
                // afterCommit 은 예외를 잡아주지 않는다 — 던지면 뒤따르는 HandBroadcaster 의 전송이 건너뛰어지고
                // 이미 커밋된 액션이 호출자에게 실패로 보인다. 예약 실패는 여기서 삼킨다.
                try {
                    reschedule(event)
                } catch (e: TaskRejectedException) {
                    // 컨텍스트 종료 중에는 차례 타이머를 걸 이유가 없고, 이를 ERROR로 두면 진짜 ERROR가 묻힌다
                    log.debug("컨텍스트 종료 중이라 차례 만료 예약을 걸지 못했습니다: tableId={}", event.tableId.value)
                } catch (e: Exception) {
                    log.error("차례 만료 예약에 실패했습니다: tableId={}", event.tableId.value, e)
                }
            }
        })
    }

    private fun reschedule(event: HandBroadcastRequested) {
        scheduled.remove(event.tableId)?.future?.cancel(false)

        // 끝난 핸드의 이벤트에서도 올린다 — 대기 중인 만료 재시도가 토큰 검사에서 걸러져야 한다.
        val token = tokens.computeIfAbsent(event.tableId) { AtomicLong() }.incrementAndGet()
        val seatNo = event.hand?.toActSeatNo ?: return
        val future = taskScheduler.schedule(
            { onExpire(event.tableId, seatNo, token) },
            Instant.now(clock).plus(TURN_TIMEOUT),
        )
        scheduled[event.tableId] = ScheduledExpiry(future, token)
    }

    /** 테이블이 닫히면 예약과 토큰을 치운다. 토큰이 사라지면 이미 깨어난 낡은 실행도 걸러진다. */
    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        scheduled.remove(event.tableId)?.future?.cancel(false)
        tokens.remove(event.tableId)
    }

    override fun remaining(tableId: TableId): Duration? {
        val future = scheduled[tableId]?.future ?: return null
        return Duration.ofMillis(maxOf(0L, future.getDelay(TimeUnit.MILLISECONDS)))
    }

    // TableExecutor 가 이 만료를 그 테이블의 실제 플레이어 액션과 같은 스레드에 직렬화하므로,
    // 이 사이에 다른 액션이 먼저 커밋돼 차례가 넘어갔더라도 ExpireTurnService 의 stale 가드
    // (toActSeatNo != seatNo 면 no-op)만으로 안전하다 - 더 이상 경합 자체가 나지 않는다.
    private fun onExpire(tableId: TableId, seatNo: Int, token: Long) {
        if (tokens[tableId]?.get() != token) return
        // 실패하면 이 만료를 대신할 이벤트가 없어 차례가 영영 안 넘어간다 — 다시 걸고, 안 되면 환불한다(그것도 다시 건다).
        val isStale = { tokens[tableId]?.get() != token }
        taskScheduler.runRetrying(
            "차례 만료 tableId=${tableId.value}", clock,
            isStale = isStale,
            onGiveUp = { cancelRetrying(tableId, isStale) },
        ) { tableExecutor.call(tableId) { expireTurnUseCase.expire(ExpireTurnCommand(tableId.value, seatNo)) } }
    }

    private fun cancelRetrying(tableId: TableId, isStale: () -> Boolean) = taskScheduler.runRetrying(
        "차례 만료 후 핸드 취소 tableId=${tableId.value}", clock,
        isStale = isStale,
        onGiveUp = { log.error("차례 만료 실패 뒤 핸드 취소·환불도 실패했습니다. 더 재시도하지 않습니다: tableId={}", tableId.value) },
    ) { tableExecutor.call(tableId) { cancelHandUseCase.cancel(CancelHandCommand(tableId.value)) } }

    companion object {
        private val log = LoggerFactory.getLogger(TurnTimer::class.java)

        // 계획서: 테이블 설정값이 될 자리. 지금은 전역 상수.
        private val TURN_TIMEOUT: Duration = Duration.ofMinutes(1)
    }
}
