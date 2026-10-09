package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
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

private class TurnTimerFakeScheduledFuture(private val delayMs: Long = 0) : ScheduledFuture<Any?> {
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
    override fun getDelay(unit: TimeUnit): Long = unit.convert(delayMs, TimeUnit.MILLISECONDS)
}

/** 실제 스레드풀 없이, schedule 로 넘어온 작업을 기록만 해뒀다가 테스트가 직접 run() 시킨다. */
private class TurnTimerFakeTaskScheduler(private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : TaskScheduler {
    class Scheduled(val task: Runnable, val time: Instant, val future: TurnTimerFakeScheduledFuture)

    val scheduledCalls = mutableListOf<Scheduled>()

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = TurnTimerFakeScheduledFuture(Duration.between(now, startTime).toMillis())
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

private class TurnTimerFakeExpireTurnUseCase : ExpireTurnUseCase {
    val calls = mutableListOf<ExpireTurnCommand>()
    override fun expire(command: ExpireTurnCommand) {
        calls += command
    }
}

private class TurnTimerFakeCancelHandUseCase : CancelHandUseCase {
    val calls = mutableListOf<CancelHandCommand>()
    override fun cancel(command: CancelHandCommand) {
        calls += command
    }
}

/** 이 테스트는 실제 동시성을 검증하지 않는다 — 그 테이블 스레드에서 바로 실행한 것처럼 인라인으로 돈다. */
private class TurnTimerFakeTableExecutor : TableExecutor {
    override fun <T> call(tableId: TableId, task: () -> T): T = task()
    override fun post(tableId: TableId, task: () -> Unit) = task()
}

private fun TurnTimer.commit(event: HandBroadcastRequested) {
    TransactionSynchronizationManager.initSynchronization()
    try {
        onHandBroadcastRequested(event)
        TransactionSynchronizationUtils.triggerAfterCommit()
    } finally {
        TransactionSynchronizationManager.clear()
    }
}

class TurnTimerTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val identityShuffler = Shuffler { it }

    private fun handWithToAct(vararg stacks: Pair<Int, Long>): Hand {
        val table = HoldemTable.create("test-table")
        for ((seatNo, buyIn) in stacks) {
            table.sitDown(userId = seatNo * 1000L, buyIn = Chips.of(buyIn))
        }
        table.moveButtonToNextOccupiedSeat()
        val handStacks = stacks.associate { (seatNo, buyIn) -> seatNo to Chips.of(buyIn) }
        val buttonSeatNo = table.buttonSeatNo!!
        val seatNos = handStacks.keys.sorted()
        fun nextSeatNo(from: Int): Int = seatNos[(seatNos.indexOf(from) + 1) % seatNos.size]
        val (smallBlindSeatNo, bigBlindSeatNo) = if (seatNos.size == 2) {
            buttonSeatNo to nextSeatNo(buttonSeatNo)
        } else {
            val sb = nextSeatNo(buttonSeatNo)
            sb to nextSeatNo(sb)
        }
        return Hand.start(handStacks, buttonSeatNo, smallBlindSeatNo, bigBlindSeatNo, table.smallBlind, table.bigBlind, identityShuffler)
    }

    private fun event(hand: Hand?, tableId: TableId = TableId(1L)): HandBroadcastRequested {
        val table = HoldemTable.create("test-table")
        return HandBroadcastRequested(tableId, table, hand)
    }

    @Test
    fun `차례가 있으면 1분 뒤로 예약된다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase()
        val timer = TurnTimer(scheduler, clock, useCase, TurnTimerFakeTableExecutor(), TurnTimerFakeCancelHandUseCase())
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.commit(event(hand))

        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(Duration.ofMinutes(1)), scheduler.scheduledCalls[0].time)
    }

    @Test
    fun `다음 이벤트가 오면 이전 예약이 취소되고 다시 잡힌다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase()
        val timer = TurnTimer(scheduler, clock, useCase, TurnTimerFakeTableExecutor(), TurnTimerFakeCancelHandUseCase())
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.commit(event(hand))
        val first = scheduler.scheduledCalls[0]
        timer.commit(event(hand))

        assertTrue(first.future.cancelled)
        assertEquals(2, scheduler.scheduledCalls.size)
    }

    @Test
    fun `remaining 은 예약 전에는 null 이고 차례가 예약되면 남은 시간을 돌려주고 차례가 없어지면 다시 null 이다`() {
        val timer = TurnTimer(TurnTimerFakeTaskScheduler(), clock, TurnTimerFakeExpireTurnUseCase(), TurnTimerFakeTableExecutor(), TurnTimerFakeCancelHandUseCase())
        val tableId = TableId(1L)
        assertNull(timer.remaining(tableId))

        timer.commit(event(handWithToAct(1 to 10_000L, 2 to 10_000L), tableId))
        assertEquals(Duration.ofMinutes(1), timer.remaining(tableId))

        timer.commit(event(null, tableId))
        assertNull(timer.remaining(tableId))
    }

    @Test
    fun `핸드가 null 이면 예약하지 않는다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase()
        val timer = TurnTimer(scheduler, clock, useCase, TurnTimerFakeTableExecutor(), TurnTimerFakeCancelHandUseCase())

        timer.commit(event(null))

        assertEquals(0, scheduler.scheduledCalls.size)
    }

    @Test
    fun `토큰이 바뀐 뒤 깨어난 stale 작업은 유스케이스를 부르지 않는다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase()
        val timer = TurnTimer(scheduler, clock, useCase, TurnTimerFakeTableExecutor(), TurnTimerFakeCancelHandUseCase())
        val tableId = TableId(1L)
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.commit(event(hand, tableId))
        val stale = scheduler.scheduledCalls[0]
        timer.commit(event(hand, tableId))
        val fresh = scheduler.scheduledCalls[1]

        // cancel() 을 안 믿고, 이미 시작된 것처럼 stale 쪽을 직접 실행시켜본다.
        stale.task.run()
        assertEquals(0, useCase.calls.size)

        fresh.task.run()
        assertEquals(1, useCase.calls.size)
        assertEquals(tableId.value, useCase.calls[0].tableId)
    }

    @Test
    fun `만료가 계속 실패하면 백오프로 다시 시도하다 5번째 실패에 핸드를 취소한다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val failing = object : ExpireTurnUseCase {
            override fun expire(command: ExpireTurnCommand) = throw IllegalStateException("db down")
        }
        val cancelUseCase = TurnTimerFakeCancelHandUseCase()
        val timer = TurnTimer(scheduler, clock, failing, TurnTimerFakeTableExecutor(), cancelUseCase)
        val tableId = TableId(1L)

        timer.commit(event(handWithToAct(1 to 10_000L, 2 to 10_000L), tableId))
        scheduler.scheduledCalls[0].task.run()

        // 1번째 실패 -> 2s 뒤 재시도, 이어서 4s, 8s, 16s
        assertEquals(fixedInstant.plusSeconds(2), scheduler.scheduledCalls[1].time)
        assertEquals(0, cancelUseCase.calls.size)
        for (i in 1..3) scheduler.scheduledCalls[i].task.run()
        assertEquals(fixedInstant.plusSeconds(16), scheduler.scheduledCalls[4].time)
        scheduler.scheduledCalls[4].task.run()

        assertEquals(5, scheduler.scheduledCalls.size)
        assertEquals(listOf(tableId.value), cancelUseCase.calls.map { it.tableId })
    }

    @Test
    fun `만료 재시도 중 새 이벤트가 오면 낡은 재시도는 멈춘다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val failing = object : ExpireTurnUseCase {
            override fun expire(command: ExpireTurnCommand) = throw IllegalStateException("db down")
        }
        val cancelUseCase = TurnTimerFakeCancelHandUseCase()
        val timer = TurnTimer(scheduler, clock, failing, TurnTimerFakeTableExecutor(), cancelUseCase)
        val tableId = TableId(1L)
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.commit(event(hand, tableId))
        scheduler.scheduledCalls[0].task.run()
        val retry = scheduler.scheduledCalls[1]
        timer.commit(event(hand, tableId))

        val before = scheduler.scheduledCalls.size
        retry.task.run()

        assertEquals(before, scheduler.scheduledCalls.size)
        assertEquals(0, cancelUseCase.calls.size)
    }

    @Test
    fun `끝난 핸드의 브로드캐스트가 오면 대기 중인 만료 재시도는 낡은 것이 된다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val failing = object : ExpireTurnUseCase {
            override fun expire(command: ExpireTurnCommand) = throw IllegalStateException("db down")
        }
        val cancelUseCase = TurnTimerFakeCancelHandUseCase()
        val timer = TurnTimer(scheduler, clock, failing, TurnTimerFakeTableExecutor(), cancelUseCase)
        val tableId = TableId(1L)

        timer.commit(event(handWithToAct(1 to 10_000L, 2 to 10_000L), tableId))
        scheduler.scheduledCalls[0].task.run()
        val retry = scheduler.scheduledCalls[1]
        timer.commit(event(null, tableId)) // 차례가 없는 이벤트(핸드 종료)

        val before = scheduler.scheduledCalls.size
        retry.task.run()

        assertEquals(before, scheduler.scheduledCalls.size)
        assertEquals(0, cancelUseCase.calls.size)
    }

    @Test
    fun `포기 뒤 핸드 취소가 실패해도 백오프로 다시 시도한다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val failing = object : ExpireTurnUseCase {
            override fun expire(command: ExpireTurnCommand) = throw IllegalStateException("db down")
        }
        var cancelAttempts = 0
        val flakyCancel = object : CancelHandUseCase {
            override fun cancel(command: CancelHandCommand) {
                if (++cancelAttempts == 1) throw IllegalStateException("db down")
            }
        }
        val timer = TurnTimer(scheduler, clock, failing, TurnTimerFakeTableExecutor(), flakyCancel)

        timer.commit(event(handWithToAct(1 to 10_000L, 2 to 10_000L)))
        scheduler.scheduledCalls[0].task.run()
        for (i in 1..4) scheduler.scheduledCalls[i].task.run() // 5번째 만료 실패 -> 첫 취소 시도(실패) -> 재시도 예약

        assertEquals(1, cancelAttempts)
        assertEquals(6, scheduler.scheduledCalls.size)
        scheduler.scheduledCalls[5].task.run()
        assertEquals(2, cancelAttempts)
    }
}
