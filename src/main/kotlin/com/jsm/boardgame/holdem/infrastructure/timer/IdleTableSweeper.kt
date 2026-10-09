package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableCommand
import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableUseCase
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 좌석이 비어 [IDLE_TABLE_TTL] 이상 지난 테이블을 닫는다. `ApplicationReadyEvent` 부터 [SWEEP_INTERVAL]
 * 간격으로 돈다(HandRecovery 와 같은 이유로 컨텍스트가 다 뜬 뒤). `@EnableScheduling` 없이 기존 `taskScheduler`
 * 에 직접 건다 — 타이머들과 같은 방식이다.
 *
 * "비기 시작한 시각"([emptySince])은 메모리에만 둔다 — 재시작하면 처음부터 다시 센다(최대 TTL 만큼 늦게 닫힐 뿐 안전하다).
 * 후보에서 빠진 테이블은 항목을 지운다. 후보였지만 유스케이스가 닫지 않은 테이블(대기열·핸드가 끼어듦)도
 * 항목을 지워 그 시점부터 다시 센다. 한 테이블이 실패해도 나머지는 계속 돌고, 실패한 테이블은 다음 스윕이 다시 시도한다.
 *
 * 닫기는 `tableExecutor.post` 로 각 테이블 스레드에 맡기고 기다리지 않는다. 고정 지연 작업은 모든 타이머(턴·다음 핸드·
 * 런아웃·연결·복구)가 공유하는 스케줄러 스레드 하나에서 도므로, 여기서 `call` 로 기다리면 느린 테이블 하나(락/문장 타임아웃
 * 3~10초)가 서버 전체 타이머를 멈춘다. 스케줄러 스레드에 남는 블로킹은 후보 조회뿐이며 statement_timeout 으로 제한된다.
 * 닫기가 테이블 스레드에서 돌기 때문에 [emptySince] 는 동시 맵이다. 아직 큐에 있는 닫기를 두고 다음 스윕이 같은 테이블을
 * 또 올릴 수 있으나 무해하다(유스케이스가 다시 확인하고, 이미 닫힌 테이블은 false).
 */
@Component
class IdleTableSweeper(
    private val taskScheduler: TaskScheduler,
    private val queries: HoldemTableQueryRepository,
    private val closeIdleTableUseCase: CloseIdleTableUseCase,
    private val tableExecutor: TableExecutor,
    private val clock: Clock,
) {
    private val emptySince = ConcurrentHashMap<Long, Instant>()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        taskScheduler.scheduleWithFixedDelay(::sweep, SWEEP_INTERVAL)
    }

    fun sweep() {
        val now = Instant.now(clock)
        val candidates = try {
            queries.findEmptyTableIds()
        } catch (e: Exception) {
            log.error("idle table sweep: 후보 조회 실패", e)
            return
        }
        emptySince.keys.retainAll(candidates.toSet())
        for (id in candidates) {
            val since = emptySince.computeIfAbsent(id) { now }
            if (Duration.between(since, now) < IDLE_TABLE_TTL) continue
            tableExecutor.post(TableId(id)) {
                try {
                    if (!closeIdleTableUseCase.close(CloseIdleTableCommand(id))) emptySince.remove(id)
                } catch (e: Exception) {
                    log.error("idle table sweep: 테이블 닫기 실패 tableId={}", id, e)
                }
            }
        }
    }

    companion object {
        val IDLE_TABLE_TTL: Duration = Duration.ofMinutes(1)
        val SWEEP_INTERVAL: Duration = Duration.ofSeconds(15)
        private val log = LoggerFactory.getLogger(IdleTableSweeper::class.java)
    }
}
