package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutUseCase
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
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

private class RunoutTimerFakeScheduledFuture : ScheduledFuture<Any?> {
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

/** 실제 스레드풀 없이, schedule 로 넘어온 작업을 기록만 해뒀다가 테스트가 직접 run() 시킨다. */
private class RunoutTimerFakeTaskScheduler : TaskScheduler {
    class Scheduled(val task: Runnable, val time: Instant, val future: RunoutTimerFakeScheduledFuture)

    val scheduledCalls = mutableListOf<Scheduled>()

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = RunoutTimerFakeScheduledFuture()
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

private class RunoutTimerFakeAdvanceRunoutUseCase : AdvanceRunoutUseCase {
    val calls = mutableListOf<AdvanceRunoutCommand>()
    var failures = 0
    override fun advance(command: AdvanceRunoutCommand) {
        calls += command
        if (failures > 0) {
            failures--
            throw IllegalStateException("db down")
        }
    }
}

private class RunoutTimerFakeCancelHandUseCase : CancelHandUseCase {
    val calls = mutableListOf<CancelHandCommand>()
    var fail = false
    override fun cancel(command: CancelHandCommand) {
        calls += command
        if (fail) throw IllegalStateException("db down")
    }
}

/** 그 테이블 스레드에서 바로 실행한 것처럼 인라인으로 돈다. */
private class RunoutTimerFakeTableExecutor : TableExecutor {
    override fun <T> call(tableId: TableId, task: () -> T): T = task()
    override fun post(tableId: TableId, task: () -> Unit) = task()

    override fun remove(tableId: TableId) {}
}

class RunoutTimerTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val stepDelay = Duration.ofSeconds(2)
    private val scheduler = RunoutTimerFakeTaskScheduler()
    private val useCase = RunoutTimerFakeAdvanceRunoutUseCase()
    private val cancelUseCase = RunoutTimerFakeCancelHandUseCase()
    private val timer = RunoutTimer(scheduler, clock, useCase, cancelUseCase, RunoutTimerFakeTableExecutor(), stepDelay)
    private val tableId = TableId(1L)

    private fun hand(allIn: Boolean): Hand {
        val stacks = mapOf(1 to Chips.of(1_000), 2 to Chips.of(1_000))
        val hand = Hand.start(stacks, 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it })
        if (allIn) {
            hand.act(1, BettingAction.RaiseTo(Chips.of(1_000)))
            hand.act(2, BettingAction.Call)
        }
        return hand
    }

    private fun event(hand: Hand?) = HandBroadcastRequested(tableId, HoldemTable.create("test-table"), hand)

    @Test
    fun `런아웃 중인 핸드만 stepDelay 뒤로 예약한다`() {
        timer.onHandBroadcastRequested(event(hand(allIn = false)))
        timer.onHandBroadcastRequested(event(null))
        assertEquals(0, scheduler.scheduledCalls.size)

        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(stepDelay), scheduler.scheduledCalls[0].time)
    }

    @Test
    fun `새 이벤트가 오면 이전 예약이 취소된다`() {
        timer.onHandBroadcastRequested(event(hand(allIn = true)))
        val first = scheduler.scheduledCalls[0]

        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        assertTrue(first.future.cancelled)
        assertEquals(2, scheduler.scheduledCalls.size)
    }

    @Test
    fun `토큰이 바뀐 뒤 깨어난 stale 작업은 걸음을 진행하지 않는다`() {
        timer.onHandBroadcastRequested(event(hand(allIn = true)))
        val stale = scheduler.scheduledCalls[0]
        timer.onHandBroadcastRequested(event(hand(allIn = true)))
        val fresh = scheduler.scheduledCalls[1]

        stale.task.run()
        assertEquals(0, useCase.calls.size)

        fresh.task.run()
        assertEquals(1, useCase.calls.size)
        assertEquals(tableId.value, useCase.calls[0].tableId)
    }

    @Test
    fun `걸음이 실패하면 같은 걸음을 다시 예약한다`() {
        useCase.failures = 1
        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        scheduler.scheduledCalls[0].task.run()

        assertEquals(2, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(stepDelay), scheduler.scheduledCalls[1].time)

        scheduler.scheduledCalls[1].task.run()
        assertEquals(2, useCase.calls.size)
    }

    @Test
    fun `걸음이 성공하면 스스로 다시 예약하지 않는다`() {
        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        scheduler.scheduledCalls[0].task.run()

        assertEquals(1, useCase.calls.size)
        assertEquals(1, scheduler.scheduledCalls.size)
    }

    @Test
    fun `연속 실패하면 stepDelay 의 지수 백오프로 다시 예약한다`() {
        useCase.failures = 4
        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        repeat(4) { scheduler.scheduledCalls[it].task.run() }

        assertEquals(
            listOf(2L, 4L, 8L, 16L).map { fixedInstant.plusSeconds(it) },
            scheduler.scheduledCalls.drop(1).map { it.time },
        )
        assertEquals(0, cancelUseCase.calls.size)
    }

    @Test
    fun `다섯 번째 연속 실패에서는 다시 예약하지 않고 핸드를 취소해 환불한다`() {
        useCase.failures = 5
        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        repeat(5) { scheduler.scheduledCalls[it].task.run() }

        assertEquals(5, scheduler.scheduledCalls.size)
        assertEquals(listOf(tableId.value), cancelUseCase.calls.map { it.tableId })
    }

    @Test
    fun `취소마저 계속 실패하면 5번 백오프로 시도한 뒤 멈춘다`() {
        cancelUseCase.fail = true
        useCase.failures = 5
        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        repeat(9) { scheduler.scheduledCalls[it].task.run() } // 진행 5회 + 취소 재시도 4회

        assertEquals(5, cancelUseCase.calls.size)
        assertEquals(9, scheduler.scheduledCalls.size)
    }

    @Test
    fun `새 이벤트가 오면 실패 횟수가 0 으로 돌아간다`() {
        useCase.failures = 4
        timer.onHandBroadcastRequested(event(hand(allIn = true)))
        repeat(4) { scheduler.scheduledCalls[it].task.run() } // 4번 실패

        timer.onHandBroadcastRequested(event(hand(allIn = true)))
        useCase.failures = 1
        scheduler.scheduledCalls.last().task.run() // 새 카운트의 첫 실패

        assertEquals(0, cancelUseCase.calls.size)
        assertEquals(fixedInstant.plusSeconds(2), scheduler.scheduledCalls.last().time) // 백오프가 처음부터다
    }

    @Test
    fun `런아웃이 아닌 이벤트도 토큰을 올려 대기 중이던 걸음을 무효로 한다`() {
        timer.onHandBroadcastRequested(event(hand(allIn = true)))
        val pending = scheduler.scheduledCalls[0]

        timer.onHandBroadcastRequested(event(null))
        pending.task.run()

        assertEquals(0, useCase.calls.size)
        assertEquals(1, scheduler.scheduledCalls.size)
    }

    @Test
    fun `forget 은 걸린 예약을 취소하고 낡은 실행을 무시하게 만든다`() {
        timer.onHandBroadcastRequested(event(hand(allIn = true)))

        timer.forget(tableId)

        assertTrue(scheduler.scheduledCalls[0].future.cancelled)
        scheduler.scheduledCalls[0].task.run()
        assertEquals(0, useCase.calls.size)
    }
}
