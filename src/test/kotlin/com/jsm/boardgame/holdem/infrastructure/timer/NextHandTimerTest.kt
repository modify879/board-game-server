package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 남은 지연(delayMillis)을 테스트가 직접 조작한다 — 실제 스레드풀 없이 [NextHandTimer.remaining] 을 검증하기 위해서다. */
private class NextHandTimerFakeScheduledFuture : ScheduledFuture<Any?> {
    var cancelled = false
        private set
    var delayMillis: Long = 0

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelled = true
        return true
    }

    override fun isCancelled(): Boolean = cancelled
    override fun isDone(): Boolean = false
    override fun get(): Any? = null
    override fun get(timeout: Long, unit: TimeUnit?): Any? = null
    override fun compareTo(other: Delayed?): Int = 0
    override fun getDelay(unit: TimeUnit): Long = unit.convert(delayMillis, TimeUnit.MILLISECONDS)
}

private class NextHandTimerFakeTaskScheduler : TaskScheduler {
    class Scheduled(val task: Runnable, val time: Instant, val future: NextHandTimerFakeScheduledFuture)

    val scheduledCalls = mutableListOf<Scheduled>()

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = NextHandTimerFakeScheduledFuture()
        scheduledCalls += Scheduled(task, startTime, future)
        return future
    }

    override fun scheduleAtFixedRate(task: Runnable, startTime: Instant, period: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()

    override fun scheduleAtFixedRate(task: Runnable, period: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()

    override fun scheduleWithFixedDelay(task: Runnable, startTime: Instant, delay: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()

    override fun scheduleWithFixedDelay(task: Runnable, delay: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()
}

private class NextHandTimerFakeStartScheduledHandUseCase : StartScheduledHandUseCase {
    val calls = mutableListOf<StartScheduledHandCommand>()
    override fun start(command: StartScheduledHandCommand) {
        calls += command
    }
}

/** 이 테스트는 실제 동시성을 검증하지 않는다 — 그 테이블 스레드에서 바로 실행한 것처럼 인라인으로 돈다. */
private class NextHandTimerFakeTableExecutor : TableExecutor {
    override fun <T> call(tableId: TableId, task: () -> T): T = task()
    override fun post(tableId: TableId, task: () -> Unit) = task()
}

class NextHandTimerTest {

    private val fixedInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)

    private val scheduler = NextHandTimerFakeTaskScheduler()
    private val useCase = NextHandTimerFakeStartScheduledHandUseCase()
    private val timer = NextHandTimer(scheduler, useCase, NextHandTimerFakeTableExecutor(), clock, nextHandDelay)
    private val tableId = TableId(1L)

    @Test
    fun `restart 는 설정된 지연 뒤로 새로 건다`() {
        timer.restart(tableId)

        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(nextHandDelay), scheduler.scheduledCalls[0].time)
    }

    @Test
    fun `restart 를 두 번 부르면 이전 예약을 취소하고 처음부터 다시 건다 — 리셋된다`() {
        timer.restart(tableId)
        val first = scheduler.scheduledCalls[0]

        timer.restart(tableId)

        assertTrue(first.future.cancelled)
        assertEquals(2, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(nextHandDelay), scheduler.scheduledCalls[1].time)
    }

    @Test
    fun `cancel 은 걸려 있던 예약을 취소하고 remaining 을 null 로 만든다`() {
        timer.restart(tableId)

        timer.cancel(tableId)

        assertTrue(scheduler.scheduledCalls[0].future.cancelled)
        assertNull(timer.remaining(tableId))
    }

    @Test
    fun `remaining 은 걸린 예약이 없으면 null 이다`() {
        assertNull(timer.remaining(tableId))
    }

    @Test
    fun `remaining 은 예약된 future 의 남은 지연을 그대로 따라가며 줄어든다`() {
        timer.restart(tableId)
        val future = scheduler.scheduledCalls[0].future

        future.delayMillis = 3_000
        assertEquals(Duration.ofMillis(3_000), timer.remaining(tableId))

        future.delayMillis = 500
        assertEquals(Duration.ofMillis(500), timer.remaining(tableId))
    }

    @Test
    fun `remaining 은 음수 지연을 0 으로 clamp 한다`() {
        timer.restart(tableId)
        scheduler.scheduledCalls[0].future.delayMillis = -50

        assertEquals(Duration.ZERO, timer.remaining(tableId))
    }

    @Test
    fun `토큰이 바뀐 뒤 깨어난 stale 작업은 유스케이스를 부르지 않고, 최신 토큰만 실행돼 예약을 정리한다`() {
        timer.restart(tableId)
        val stale = scheduler.scheduledCalls[0]
        timer.restart(tableId)
        val fresh = scheduler.scheduledCalls[1]

        stale.task.run()
        assertEquals(0, useCase.calls.size)

        fresh.task.run()
        assertEquals(1, useCase.calls.size)
        assertEquals(tableId.value, useCase.calls[0].tableId)
        assertNull(timer.remaining(tableId))
    }

    @Test
    fun `발화는 벽시계와 무관하다 — Clock 이 크게 어긋나도 정상 발화한다`() {
        val skewedScheduler = NextHandTimerFakeTaskScheduler()
        val skewedUseCase = NextHandTimerFakeStartScheduledHandUseCase()
        val farPastClock = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC)
        val skewedTimer = NextHandTimer(skewedScheduler, skewedUseCase, NextHandTimerFakeTableExecutor(), farPastClock, nextHandDelay)

        skewedTimer.restart(tableId)
        skewedScheduler.scheduledCalls[0].task.run()

        assertEquals(1, skewedUseCase.calls.size)
    }
}
