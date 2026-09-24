package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class NextHandTimerFakeScheduledFuture : ScheduledFuture<Any?> {
    var cancelled = false
        private set

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelled = true
        return true
    }

    override fun isCancelled(): Boolean = cancelled
    override fun isDone(): Boolean = false
    override fun get(): Any? = null
    override fun get(timeout: Long, unit: TimeUnit?): Any? = null
    override fun compareTo(other: Delayed?): Int = 0
    override fun getDelay(unit: TimeUnit): Long = 0
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

private class NextHandTimerFakeStartScheduledHandUseCase(private val throwConcurrentUpdate: Boolean = false) : StartScheduledHandUseCase {
    val calls = mutableListOf<StartScheduledHandCommand>()
    override fun start(command: StartScheduledHandCommand) {
        if (throwConcurrentUpdate) throw ConcurrentTableUpdateException("test")
        calls += command
    }
}

class NextHandTimerTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")

    private fun eventWithNextHandAt(tableId: TableId, nextHandAt: Instant?): HandBroadcastRequested {
        var table = HoldemTable.create("test-table")
        if (nextHandAt != null) table.scheduleNextHand(nextHandAt)
        return HandBroadcastRequested(tableId, table, hand = null)
    }

    @Test
    fun `nextHandAt 이 있는 이벤트를 받으면 그 시각으로 예약된다`() {
        val scheduler = NextHandTimerFakeTaskScheduler()
        val useCase = NextHandTimerFakeStartScheduledHandUseCase()
        val timer = NextHandTimer(scheduler, useCase)
        val at = fixedInstant.plus(Duration.ofSeconds(5))

        timer.onHandBroadcastRequested(eventWithNextHandAt(TableId(1L), at))

        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(at, scheduler.scheduledCalls[0].time)
    }

    @Test
    fun `nextHandAt 이 없는 이벤트는 예약하지 않는다`() {
        val scheduler = NextHandTimerFakeTaskScheduler()
        val useCase = NextHandTimerFakeStartScheduledHandUseCase()
        val timer = NextHandTimer(scheduler, useCase)

        timer.onHandBroadcastRequested(eventWithNextHandAt(TableId(1L), null))

        assertEquals(0, scheduler.scheduledCalls.size)
    }

    @Test
    fun `같은 테이블에 새 이벤트가 오면 이전 예약이 취소되고 다시 잡힌다`() {
        val scheduler = NextHandTimerFakeTaskScheduler()
        val useCase = NextHandTimerFakeStartScheduledHandUseCase()
        val timer = NextHandTimer(scheduler, useCase)
        val tableId = TableId(1L)

        timer.onHandBroadcastRequested(eventWithNextHandAt(tableId, fixedInstant.plus(Duration.ofSeconds(5))))
        val first = scheduler.scheduledCalls[0]
        timer.onHandBroadcastRequested(eventWithNextHandAt(tableId, fixedInstant.plus(Duration.ofSeconds(10))))

        assertTrue(first.future.cancelled)
        assertEquals(2, scheduler.scheduledCalls.size)
    }

    @Test
    fun `nextHandAt 이 없는 새 이벤트가 오면 기존 예약을 취소만 하고 다시 잡지 않는다`() {
        val scheduler = NextHandTimerFakeTaskScheduler()
        val useCase = NextHandTimerFakeStartScheduledHandUseCase()
        val timer = NextHandTimer(scheduler, useCase)
        val tableId = TableId(1L)

        timer.onHandBroadcastRequested(eventWithNextHandAt(tableId, fixedInstant.plus(Duration.ofSeconds(5))))
        val first = scheduler.scheduledCalls[0]
        timer.onHandBroadcastRequested(eventWithNextHandAt(tableId, null))

        assertTrue(first.future.cancelled)
        assertEquals(1, scheduler.scheduledCalls.size)
    }

    @Test
    fun `토큰이 바뀐 뒤 깨어난 stale 작업은 유스케이스를 부르지 않는다`() {
        val scheduler = NextHandTimerFakeTaskScheduler()
        val useCase = NextHandTimerFakeStartScheduledHandUseCase()
        val timer = NextHandTimer(scheduler, useCase)
        val tableId = TableId(1L)

        timer.scheduleAt(tableId, fixedInstant.plus(Duration.ofSeconds(5)))
        val stale = scheduler.scheduledCalls[0]
        timer.scheduleAt(tableId, fixedInstant.plus(Duration.ofSeconds(10)))
        val fresh = scheduler.scheduledCalls[1]

        stale.task.run()
        assertEquals(0, useCase.calls.size)

        fresh.task.run()
        assertEquals(1, useCase.calls.size)
        assertEquals(tableId.value, useCase.calls[0].tableId)
    }

    @Test
    fun `유스케이스가 CONCURRENT_TABLE_UPDATE 로 실패해도 예약된 작업은 예외를 던지지 않는다`() {
        val scheduler = NextHandTimerFakeTaskScheduler()
        val useCase = NextHandTimerFakeStartScheduledHandUseCase(throwConcurrentUpdate = true)
        val timer = NextHandTimer(scheduler, useCase)

        timer.scheduleAt(TableId(1L), fixedInstant.plus(Duration.ofSeconds(5)))

        scheduler.scheduledCalls[0].task.run() // 예외가 새어나오면 이 줄에서 테스트가 실패한다
    }
}
