package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 다음 핸드 자동 시작 타이머. [TurnTimer] 와 예약/토큰 패턴이 같다 — 테이블별 단조 증가 토큰으로
 * cancel() 이 못 막은 stale 실행을 걸러낸다.
 *
 * 상대 시간(단조 시계)만 쓴다: [Instant.now] 은 [taskScheduler] 가 지연을 계산하는 데 딱 한 번만
 * 쓰이고, 발화 시점에 다시 벽시계로 비교하지 않는다. WSL2 호스트의 벽시계가 뒤로 튀어도(문서
 * 배경 참고) 이미 잡힌 지연 자체는 영향을 받지 않는다 — [ScheduledFuture.getDelay] 도 단조 시계
 * 기반이라 [remaining] 도 안전하다.
 *
 * 재시작하면 이 인메모리 예약은 사라진다 — [com.jsm.boardgame.holdem.infrastructure.recovery.HandRecovery]
 * 가 부팅 시 조건에 맞는 테이블마다 [restart] 로 새로 건다.
 *
 * [startScheduledHandUseCase] 는 `@Lazy` 로 받는다 — `StartScheduledHandService` 가 물고 있는
 * `HandStarter`/`HandSettler` 가 이 클래스가 구현하는 [NextHandCountdown] 을 되받아 의존하므로,
 * 그대로 두면 생성자 주입 순환(NextHandTimer → StartScheduledHandUseCase → HandStarter →
 * NextHandCountdown → NextHandTimer)이 된다. `@Lazy` 는 실제 첫 호출( [onFire] )때까지 빈 조회를 미뤄
 * 그 순환을 끊는다.
 */
@Component
class NextHandTimer(
    private val taskScheduler: TaskScheduler,
    @Lazy private val startScheduledHandUseCase: StartScheduledHandUseCase,
    private val tableExecutor: TableExecutor,
    private val clock: Clock,
    @Value("\${app.holdem.next-hand-delay}") private val nextHandDelay: Duration,
) : NextHandCountdown {
    private class ScheduledStart(val future: ScheduledFuture<*>, val token: Long)

    private val scheduled = ConcurrentHashMap<TableId, ScheduledStart>()
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    override fun restart(tableId: TableId) {
        cancel(tableId)
        val token = tokens.computeIfAbsent(tableId) { AtomicLong() }.incrementAndGet()
        // ponytail: 호출자의 트랜잭션 커밋 전에 건다. 그 트랜잭션이 롤백돼도 이 예약은 그대로
        // 남지만 해롭지 않다 — 발화 시 onFire 가 tableExecutor 안에서 실제 조건(핸드 없음, 후보
        // 2명 이상)을 다시 확인하기 때문이다.
        val future = taskScheduler.schedule({ onFire(tableId, token) }, Instant.now(clock).plus(nextHandDelay))
        scheduled[tableId] = ScheduledStart(future, token)
    }

    override fun cancel(tableId: TableId) {
        scheduled.remove(tableId)?.future?.cancel(false)
    }

    /** 테이블이 닫히면 예약과 토큰을 치운다. 토큰이 사라지면 이미 깨어난 낡은 실행도 걸러진다. */
    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        cancel(event.tableId)
        tokens.remove(event.tableId)
    }

    override fun remaining(tableId: TableId): Duration? {
        val future = scheduled[tableId]?.future ?: return null
        return Duration.ofMillis(maxOf(0, future.getDelay(TimeUnit.MILLISECONDS)))
    }

    // TableExecutor 가 이 시작을 그 테이블의 다른 모든 명령과 같은 스레드에 직렬화하므로 더 이상
    // 경합이 나지 않는다. scheduled 항목은 시작 시도 직전에 치우므로 시도 중에 카운트다운이 남아 보이지 않는다.
    // 실패하면 이 시작을 대신할 이벤트가 없어 테이블이 영영 논다 — 일반 카운트다운을 다시 걸어
    // (cancel()/remaining() 도 그대로 동작) 카운트다운 주기로 계속 재시도한다.
    private fun onFire(tableId: TableId, token: Long) {
        try {
            tableExecutor.call(tableId) {
                if (tokens[tableId]?.get() != token) return@call
                scheduled.remove(tableId)
                startScheduledHandUseCase.start(StartScheduledHandCommand(tableId.value))
            }
        } catch (e: InterruptedException) {
            // 종료 중 스케줄러가 인터럽트한 것이다 — 다시 걸지 않는다
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log.error("다음 핸드 시작에 실패해 카운트다운을 다시 겁니다: tableId={}", tableId.value, e)
            if (tokens[tableId]?.get() != token) return
            try {
                restart(tableId)
            } catch (r: TaskRejectedException) {
                // 컨텍스트 종료 중에는 다시 걸 이유가 없고, 이를 ERROR로 두면 진짜 ERROR가 묻힌다
                log.debug("컨텍스트 종료 중이라 다음 핸드 예약을 걸지 못했습니다: tableId={}", tableId.value)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NextHandTimer::class.java)
    }
}
