package com.jsm.boardgame.holdem.infrastructure.timer

import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("com.jsm.boardgame.holdem.infrastructure.timer.RetryingTask")

/**
 * [task] 를 지금 돌리고, 던지면 base*2^(n-1) 뒤 다시 건다. 스케줄러 스레드에서 불린다고 가정한다.
 * [isStale] 이 true 가 되면 조용히 멈추고, [maxAttempts] 번째 실패면 [onGiveUp] 을 부른다.
 * [name] 은 로그용(예: "런아웃 tableId=1"). [onGiveUp] 이 던져도 삼킨다 — 더 할 수 있는 게 없다.
 */
internal fun TaskScheduler.runRetrying(
    name: String,
    clock: Clock,
    base: Duration = Duration.ofSeconds(2),
    maxAttempts: Int = 5,
    isStale: () -> Boolean,
    onGiveUp: (Exception) -> Unit,
    task: () -> Unit,
) = attempt(1, name, clock, base, maxAttempts, isStale, onGiveUp, task)

private fun TaskScheduler.attempt(
    n: Int,
    name: String,
    clock: Clock,
    base: Duration,
    maxAttempts: Int,
    isStale: () -> Boolean,
    onGiveUp: (Exception) -> Unit,
    task: () -> Unit,
) {
    if (isStale()) return
    try {
        task()
    } catch (e: InterruptedException) {
        // 종료 중 스케줄러가 인터럽트한 것이다 — 재시도도, 포기 처리(취소·환불)도 하지 않는다
        Thread.currentThread().interrupt()
        log.debug("인터럽트되어 재시도를 멈춥니다: {}", name)
    } catch (e: Exception) {
        if (isStale()) return
        if (n >= maxAttempts) {
            log.error("{} {}회 연속 실패해 포기합니다", name, n, e)
            try {
                onGiveUp(e)
            } catch (g: Exception) {
                log.error("{} 포기 처리도 실패했습니다. 더 재시도하지 않습니다", name, g)
            }
            return
        }
        val delay = base.multipliedBy(1L shl (n - 1))
        log.error("{} 실패해 {} 뒤 다시 시도합니다: attempt={}", name, delay, n, e)
        try {
            schedule({ attempt(n + 1, name, clock, base, maxAttempts, isStale, onGiveUp, task) }, Instant.now(clock).plus(delay))
        } catch (r: TaskRejectedException) {
            // 컨텍스트 종료 중에는 다시 걸 이유가 없고, 이를 ERROR로 두면 진짜 ERROR가 묻힌다
            log.debug("컨텍스트 종료 중이라 재시도 예약을 걸지 못했습니다: {}", name)
        }
    }
}
