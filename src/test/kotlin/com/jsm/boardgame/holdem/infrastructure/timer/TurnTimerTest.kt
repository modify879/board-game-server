package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
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

private class TurnTimerFakeScheduledFuture : ScheduledFuture<Any?> {
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
private class TurnTimerFakeTaskScheduler : TaskScheduler {
    class Scheduled(val task: Runnable, val time: Instant, val future: TurnTimerFakeScheduledFuture)

    val scheduledCalls = mutableListOf<Scheduled>()

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = TurnTimerFakeScheduledFuture()
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

private class TurnTimerFakeExpireTurnUseCase(private val throwConcurrentUpdate: Boolean = false) : ExpireTurnUseCase {
    val calls = mutableListOf<ExpireTurnCommand>()
    override fun expire(command: ExpireTurnCommand) {
        if (throwConcurrentUpdate) throw ConcurrentTableUpdateException("test")
        calls += command
    }
}

class TurnTimerTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val identityShuffler = Shuffler { it }

    private fun handWithToAct(vararg stacks: Pair<Int, Long>): Hand {
        val table = HoldemTable.create("test-table")
        for ((seatNo, buyIn) in stacks) {
            table.sitDown(seatNo, userId = seatNo * 1000L, buyIn = Chips.of(buyIn))
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
        val timer = TurnTimer(scheduler, clock, useCase)
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.onHandBroadcastRequested(event(hand))

        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(Duration.ofMinutes(1)), scheduler.scheduledCalls[0].time)
    }

    @Test
    fun `다음 이벤트가 오면 이전 예약이 취소되고 다시 잡힌다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase()
        val timer = TurnTimer(scheduler, clock, useCase)
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.onHandBroadcastRequested(event(hand))
        val first = scheduler.scheduledCalls[0]
        timer.onHandBroadcastRequested(event(hand))

        assertTrue(first.future.cancelled)
        assertEquals(2, scheduler.scheduledCalls.size)
    }

    @Test
    fun `핸드가 null 이면 예약하지 않는다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase()
        val timer = TurnTimer(scheduler, clock, useCase)

        timer.onHandBroadcastRequested(event(null))

        assertEquals(0, scheduler.scheduledCalls.size)
    }

    @Test
    fun `토큰이 바뀐 뒤 깨어난 stale 작업은 유스케이스를 부르지 않는다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase()
        val timer = TurnTimer(scheduler, clock, useCase)
        val tableId = TableId(1L)
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.onHandBroadcastRequested(event(hand, tableId))
        val stale = scheduler.scheduledCalls[0]
        timer.onHandBroadcastRequested(event(hand, tableId))
        val fresh = scheduler.scheduledCalls[1]

        // cancel() 을 안 믿고, 이미 시작된 것처럼 stale 쪽을 직접 실행시켜본다.
        stale.task.run()
        assertEquals(0, useCase.calls.size)

        fresh.task.run()
        assertEquals(1, useCase.calls.size)
        assertEquals(tableId.value, useCase.calls[0].tableId)
    }

    @Test
    fun `유스케이스가 CONCURRENT_TABLE_UPDATE 로 실패해도 만료 작업은 예외를 던지지 않는다`() {
        val scheduler = TurnTimerFakeTaskScheduler()
        val useCase = TurnTimerFakeExpireTurnUseCase(throwConcurrentUpdate = true)
        val timer = TurnTimer(scheduler, clock, useCase)
        val hand = handWithToAct(1 to 10_000L, 2 to 10_000L)

        timer.onHandBroadcastRequested(event(hand))
        val scheduledTask = scheduler.scheduledCalls[0]

        scheduledTask.task.run()
    }
}
