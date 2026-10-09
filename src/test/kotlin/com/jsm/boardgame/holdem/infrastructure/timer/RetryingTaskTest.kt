package com.jsm.boardgame.holdem.infrastructure.timer

import org.springframework.core.task.TaskRejectedException
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
import kotlin.test.assertTrue

private class RetryingTaskFakeScheduledFuture : ScheduledFuture<Any?> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = false
    override fun get(): Any? = null
    override fun get(timeout: Long, unit: TimeUnit?): Any? = null
    override fun compareTo(other: Delayed?): Int = 0
    override fun getDelay(unit: TimeUnit): Long = 0
}

/** schedule 로 넘어온 작업을 기록만 해뒀다가 테스트가 직접 run() 시킨다. [reject] 면 TaskRejectedException 을 던진다. */
private class RetryingTaskFakeTaskScheduler : TaskScheduler {
    class Scheduled(val task: Runnable, val time: Instant)

    val calls = mutableListOf<Scheduled>()
    var reject = false

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        if (reject) throw TaskRejectedException("shutdown")
        calls += Scheduled(task, startTime)
        return RetryingTaskFakeScheduledFuture()
    }

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleAtFixedRate(task: Runnable, startTime: Instant, period: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()

    override fun scheduleAtFixedRate(task: Runnable, period: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleWithFixedDelay(task: Runnable, startTime: Instant, delay: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()

    override fun scheduleWithFixedDelay(task: Runnable, delay: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
}

class RetryingTaskTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scheduler = RetryingTaskFakeTaskScheduler()
    private var runs = 0
    private var gaveUp = 0

    private fun runFailing(maxAttempts: Int = 3, stale: () -> Boolean = { false }, onGiveUp: (Exception) -> Unit = { gaveUp++ }) =
        scheduler.runRetrying("test", clock, Duration.ofSeconds(2), maxAttempts, stale, onGiveUp) {
            runs++
            throw IllegalStateException("fail")
        }

    @Test
    fun `성공하면 한 번만 실행하고 아무것도 예약하지 않는다`() {
        scheduler.runRetrying("test", clock, Duration.ofSeconds(2), 3, { false }, { gaveUp++ }) { runs++ }

        assertEquals(1, runs)
        assertEquals(0, scheduler.calls.size)
    }

    @Test
    fun `실패하면 지연을 두 배씩 늘려 다시 걸고 마지막 실패에 포기 처리를 부른다`() {
        runFailing(maxAttempts = 3)
        assertEquals(now.plusSeconds(2), scheduler.calls[0].time)
        scheduler.calls[0].task.run()
        assertEquals(now.plusSeconds(4), scheduler.calls[1].time)
        assertEquals(0, gaveUp)
        scheduler.calls[1].task.run()

        assertEquals(3, runs)
        assertEquals(2, scheduler.calls.size)
        assertEquals(1, gaveUp)
    }

    @Test
    fun `낡았으면 실행도 재시도도 포기 처리도 하지 않는다`() {
        var stale = false
        runFailing(stale = { stale })
        stale = true
        scheduler.calls[0].task.run()

        assertEquals(1, runs)
        assertEquals(1, scheduler.calls.size)
        assertEquals(0, gaveUp)
    }

    @Test
    fun `포기 처리가 던져도 밖으로 전파하지 않는다`() {
        runFailing(maxAttempts = 1, onGiveUp = { throw IllegalStateException("cancel failed") })

        assertEquals(1, runs)
    }

    @Test
    fun `재시도 예약이 거절돼도 전파하지 않는다`() {
        scheduler.reject = true

        runFailing()

        assertEquals(1, runs)
        assertEquals(0, gaveUp)
    }

    @Test
    fun `인터럽트되면 재시도도 포기 처리도 없이 인터럽트 플래그를 복원한다`() {
        scheduler.runRetrying("test", clock, Duration.ofSeconds(2), 3, { false }, { gaveUp++ }) {
            runs++
            throw InterruptedException()
        }

        try {
            assertEquals(1, runs)
            assertEquals(0, scheduler.calls.size)
            assertEquals(0, gaveUp)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // 다른 테스트에 플래그가 새지 않게 지운다
        }
    }
}
