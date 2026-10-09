package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceUseCase
import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.security.Principal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ConnectionTimerFakeScheduledFuture : ScheduledFuture<Any?> {
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
private class ConnectionTimerFakeTaskScheduler : TaskScheduler {
    class Scheduled(val task: Runnable, val time: Instant, val future: ConnectionTimerFakeScheduledFuture)

    val scheduledCalls = mutableListOf<Scheduled>()

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = ConnectionTimerFakeScheduledFuture()
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

private class ConnectionTimerFakeUpdateSeatPresenceUseCase : UpdateSeatPresenceUseCase {
    val calls = mutableListOf<UpdateSeatPresenceCommand>()
    override fun update(command: UpdateSeatPresenceCommand) {
        calls += command
    }
}

private class ConnectionTimerFakeExpireConnectionUseCase(
    private val reservedTableId: TableId?,
) : ExpireConnectionUseCase {
    val calls = mutableListOf<ExpireConnectionCommand>()
    override fun expire(command: ExpireConnectionCommand): TableId? {
        calls += command
        return reservedTableId
    }
}

private class ConnectionTimerFailingExpireConnectionUseCase : ExpireConnectionUseCase {
    override fun expire(command: ExpireConnectionCommand): TableId? = throw IllegalStateException("db down")
}

private class ConnectionTimerFakeStandUpUseCase : StandUpUseCase {
    val calls = mutableListOf<StandUpCommand>()
    override fun standUp(command: StandUpCommand) {
        calls += command
    }
}

private class ConnectionTimerFakePrincipal(private val id: Long) : Principal {
    override fun getName(): String = id.toString()
}

private class ConnectionTimerFakeJoinQueueNotifier : JoinQueueNotifier {
    val positionsCalls = mutableListOf<Pair<TableId, List<JoinQueueEntry>>>()
    override fun notifySeated(tableId: TableId, userId: UserId, seatNo: Int) {}
    override fun notifyDropped(tableId: TableId, userId: UserId, errorCode: ErrorCode) {}
    override fun notifyPositions(tableId: TableId, entries: List<JoinQueueEntry>) {
        positionsCalls += tableId to entries
    }
}

private class ConnectionTimerFakeUserConnections : UserConnections {
    val hasOtherSessionUserIds = mutableSetOf<UserId>()

    val connectedUserIds = mutableSetOf<UserId>()

    override fun isConnected(userId: UserId): Boolean = userId in connectedUserIds

    override fun hasOtherSession(userId: UserId, excludingSessionId: String): Boolean = userId in hasOtherSessionUserIds
}

/** 이 파일의 테스트는 스레드를 따로 띄우지 않는다 — 그 테이블 스레드에서 바로 실행한 것처럼 인라인으로 돈다. */
private class ConnectionTimerFakeTableExecutor : TableExecutor {
    override fun <T> call(tableId: TableId, task: () -> T): T = task()
    override fun post(tableId: TableId, task: () -> Unit) = task()
}

private class ConnectionTimerFakeHoldemTableRepository : HoldemTableRepository {
    val seatedUserIds = mutableListOf<UserId>()

    override fun findById(id: TableId): HoldemTable? = null
    override fun findByUserId(userId: UserId): HoldemTable? = null

    override fun save(table: HoldemTable): HoldemTable = table
    override fun findAllSeatedUserIds(): List<UserId> = seatedUserIds
    override fun findAllTableIds(): List<TableId> = emptyList()
}

class ConnectionTimerTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val identityShuffler = Shuffler { it }
    private val fakeMessage = MessageBuilder.withPayload(ByteArray(0)).build()
    private val tables = ConnectionTimerFakeHoldemTableRepository()

    private fun disconnectEvent(userId: Long?) = SessionDisconnectEvent(
        this,
        fakeMessage,
        "session-1",
        CloseStatus.NORMAL,
        userId?.let { ConnectionTimerFakePrincipal(it) },
    )

    private fun connectedEvent(userId: Long) =
        SessionConnectedEvent(this, fakeMessage, ConnectionTimerFakePrincipal(userId))

    private fun handInProgress(): Hand {
        val table = HoldemTable.create("test-table")
        table.sitDown(UserId(1000L), Chips.of(10_000))
        table.sitDown(UserId(2000L), Chips.of(10_000))
        table.moveButtonToNextOccupiedSeat()
        val stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000))
        val hand = Hand.start(
            stacks = stacks,
            buttonSeatNo = table.buttonSeatNo!!,
            smallBlindSeatNo = table.buttonSeatNo,
            bigBlindSeatNo = stacks.keys.single { it != table.buttonSeatNo },
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            shuffler = identityShuffler,
        )
        while (hand.isRunningOut) hand.advanceRunout() // 올인은 런아웃을 거쳐야 끝난다
        return hand
    }

    /** 8000 바이인에 핸드 스택만 100 으로 줘서 블라인드만으로 양쪽이 곧장 올인되게 만든다(기존 HoldemViewAssemblerTest 와 동일한 트릭). */
    private fun finishedHand(): Hand {
        val table = HoldemTable.create("test-table")
        table.sitDown(UserId(1000L), Chips.of(8_000))
        table.sitDown(UserId(2000L), Chips.of(8_000))
        table.moveButtonToNextOccupiedSeat()
        val stacks = mapOf(1 to Chips.of(100), 2 to Chips.of(100))
        val hand = Hand.start(
            stacks = stacks,
            buttonSeatNo = table.buttonSeatNo!!,
            smallBlindSeatNo = table.buttonSeatNo,
            bigBlindSeatNo = stacks.keys.single { it != table.buttonSeatNo },
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            shuffler = identityShuffler,
        )
        while (hand.isRunningOut) hand.advanceRunout() // 올인은 런아웃을 거쳐야 끝난다
        return hand
    }

    @Test
    fun `연결이 끊기면 DISCONNECTED 로 표시하고 3분 뒤로 만료를 예약한다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))

        assertEquals(1, presenceUseCase.calls.size)
        assertEquals(42L, presenceUseCase.calls[0].userId)
        assertEquals(SeatPresence.DISCONNECTED.name, presenceUseCase.calls[0].presence)
        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(Duration.ofMinutes(3)), scheduler.scheduledCalls[0].time)
        assertTrue(notifier.positionsCalls.isEmpty())
    }

    @Test
    fun `대기열에 있던 사용자가 연결을 끊으면 대기열에서 빠지고 남은 사람의 순번이 알려진다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val tableId = TableId(5L)
        joinQueue.enqueue(tableId, UserId(42L), false)
        joinQueue.enqueue(tableId, UserId(43L), false)
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))

        assertEquals(false, joinQueue.isQueued(UserId(42L)))
        assertEquals(true, joinQueue.isQueued(UserId(43L)))
        assertEquals(1, notifier.positionsCalls.size)
        assertEquals(listOf(UserId(43L)), notifier.positionsCalls.last().second.map { it.userId })
    }

    @Test
    fun `다른 세션이 살아 있으면 연결이 끊겨도 대기열에서 빠지지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val userConnections = ConnectionTimerFakeUserConnections().apply { hasOtherSessionUserIds += UserId(42L) }
        val tableId = TableId(5L)
        joinQueue.enqueue(tableId, UserId(42L), false)
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, userConnections, ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))

        assertEquals(true, joinQueue.isQueued(UserId(42L)))
        assertTrue(notifier.positionsCalls.isEmpty())
    }

    @Test
    fun `유일한 세션이 끊기면 대기열에서 빠진다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val userConnections = ConnectionTimerFakeUserConnections()
        val tableId = TableId(5L)
        joinQueue.enqueue(tableId, UserId(42L), false)
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, userConnections, ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))

        assertEquals(false, joinQueue.isQueued(UserId(42L)))
        assertEquals(1, notifier.positionsCalls.size)
    }

    @Test
    fun `재접속하면 예약이 취소되고 SEATED 로 돌아온다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        val scheduledExpiry = scheduler.scheduledCalls[0]
        timer.onSessionConnected(connectedEvent(42L))

        assertTrue(scheduledExpiry.future.cancelled)
        assertEquals(SeatPresence.SEATED.name, presenceUseCase.calls.last().presence)

        // cancel() 이 못 막았다고 가정하고 stale 작업을 직접 실행해도 유스케이스가 안 불려야 한다.
        scheduledExpiry.task.run()
        assertEquals(0, expireUseCase.calls.size)
    }

    @Test
    fun `핸드가 없을 때 만료되면 즉시 퇴장을 위임하고 예약은 남기지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        scheduler.scheduledCalls[0].task.run()

        assertEquals(1, expireUseCase.calls.size)
        assertEquals(42L, expireUseCase.calls[0].userId)

        // 예약이 없으니 이후 아무 HandBroadcastRequested 가 와도 StandUpUseCase 는 불리지 않는다.
        val table = HoldemTable.create("t")
        timer.onHandBroadcastRequested(HandBroadcastRequested(TableId(1L), table, null))
        assertEquals(0, standUpUseCase.calls.size)
    }

    @Test
    fun `핸드 진행 중 만료는 즉시 퇴장시키지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val tableId = TableId(7L)
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = tableId)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        scheduler.scheduledCalls[0].task.run()

        assertEquals(0, standUpUseCase.calls.size)
    }

    @Test
    fun `핸드가 끝난 뒤 예약된 퇴장이 실제로 실행된다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val tableId = TableId(7L)
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = tableId)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        scheduler.scheduledCalls[0].task.run() // 핸드 진행 중 -> 예약만 남는다

        val table = HoldemTable.create("t")
        timer.onHandBroadcastRequested(HandBroadcastRequested(tableId, table, finishedHand()))

        assertEquals(1, standUpUseCase.calls.size)
        assertEquals(42L, standUpUseCase.calls[0].userId)
    }

    @Test
    fun `핸드가 진행 중이면 예약된 퇴장이 실행되지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val tableId = TableId(7L)
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = tableId)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        scheduler.scheduledCalls[0].task.run() // 핸드 진행 중 -> 예약만 남는다

        val table = HoldemTable.create("t")
        timer.onHandBroadcastRequested(HandBroadcastRequested(tableId, table, handInProgress()))

        assertEquals(0, standUpUseCase.calls.size)
    }

    @Test
    fun `principal 이 없는 세션 이벤트는 조용히 무시한다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(null))

        assertEquals(0, presenceUseCase.calls.size)
        assertEquals(0, scheduler.scheduledCalls.size)
    }

    @Test
    fun `stale 토큰 작업은 유스케이스를 부르지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, tables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        val stale = scheduler.scheduledCalls[0]
        timer.onSessionDisconnect(disconnectEvent(42L))
        val fresh = scheduler.scheduledCalls[1]

        stale.task.run()
        assertEquals(0, expireUseCase.calls.size)

        fresh.task.run()
        assertEquals(1, expireUseCase.calls.size)
    }

    @Test
    fun `유예 중인 사용자는 연결이 끊겨도 감시가 걸리지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, ConnectionTimerFakeHoldemTableRepository(), joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.suspendWatch(setOf(UserId(42L)))
        timer.onSessionDisconnect(disconnectEvent(42L))

        assertEquals(0, presenceUseCase.calls.size)
        assertEquals(0, scheduler.scheduledCalls.size)
    }

    @Test
    fun `부팅 시 앉아 있던 사용자 전원에게 연결 감시를 건다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val bootTables = ConnectionTimerFakeHoldemTableRepository().apply { seatedUserIds += listOf(UserId(11L), UserId(22L)) }
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, bootTables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onApplicationReady()

        assertEquals(2, presenceUseCase.calls.size)
        assertTrue(presenceUseCase.calls.all { it.presence == SeatPresence.DISCONNECTED.name })
        assertEquals(setOf(11L, 22L), presenceUseCase.calls.map { it.userId }.toSet())
        assertEquals(2, scheduler.scheduledCalls.size)
    }

    @Test
    fun `부팅 후 아무도 접속하지 않으면 만료되어 퇴장 처리로 이어진다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val bootTables = ConnectionTimerFakeHoldemTableRepository().apply { seatedUserIds += UserId(99L) }
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, bootTables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onApplicationReady()
        scheduler.scheduledCalls[0].task.run()

        assertEquals(1, expireUseCase.calls.size)
        assertEquals(99L, expireUseCase.calls[0].userId)
    }

    @Test
    fun `부팅 후 접속한 사용자는 감시가 취소되고 SEATED 로 돌아온다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val bootTables = ConnectionTimerFakeHoldemTableRepository().apply { seatedUserIds += UserId(42L) }
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, bootTables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onApplicationReady()
        val scheduledExpiry = scheduler.scheduledCalls[0]
        timer.onSessionConnected(connectedEvent(42L))

        assertTrue(scheduledExpiry.future.cancelled)
        assertEquals(SeatPresence.SEATED.name, presenceUseCase.calls.last().presence)

        scheduledExpiry.task.run()
        assertEquals(0, expireUseCase.calls.size)
    }

    @Test
    fun `복구 유예 중인 사용자는 부팅 시 감시 대상에서 빠진다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val bootTables = ConnectionTimerFakeHoldemTableRepository().apply { seatedUserIds += listOf(UserId(1L), UserId(2L)) }
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, bootTables, joinQueue, notifier, ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.suspendWatch(setOf(UserId(1L)))
        timer.onApplicationReady()

        assertEquals(1, presenceUseCase.calls.size)
        assertEquals(2L, presenceUseCase.calls[0].userId)
        assertEquals(1, scheduler.scheduledCalls.size)
    }

    @Test
    fun `부팅 시점에 이미 연결된 착석자는 감시하지 않고 연결 안 된 착석자만 감시한다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val bootTables = ConnectionTimerFakeHoldemTableRepository().apply { seatedUserIds += listOf(UserId(42L), UserId(99L)) }
        val joinQueue = InMemoryJoinQueue()
        val notifier = ConnectionTimerFakeJoinQueueNotifier()
        val userConnections = ConnectionTimerFakeUserConnections()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, bootTables, joinQueue, notifier, userConnections, ConnectionTimerFakeTableExecutor())

        // Ready 전에 재접속이 먼저 처리된 순서 - 연결 이벤트 다음에 부팅 시딩이 돈다.
        userConnections.connectedUserIds += UserId(42L)
        timer.onSessionConnected(connectedEvent(42L))
        timer.onApplicationReady()

        assertEquals(listOf(SeatPresence.SEATED.name, SeatPresence.DISCONNECTED.name), presenceUseCase.calls.map { it.presence })
        assertEquals(listOf(42L, 99L), presenceUseCase.calls.map { it.userId })
        assertEquals(1, scheduler.scheduledCalls.size)
        scheduler.scheduledCalls[0].task.run()
        assertEquals(listOf(99L), expireUseCase.calls.map { it.userId })
    }

    @Test
    fun `만료 처리가 실패하면 연결 감시를 다시 건다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, ConnectionTimerFailingExpireConnectionUseCase(), ConnectionTimerFakeStandUpUseCase(), tables, InMemoryJoinQueue(), ConnectionTimerFakeJoinQueueNotifier(), ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        scheduler.scheduledCalls[0].task.run()

        assertEquals(2, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(Duration.ofMinutes(3)), scheduler.scheduledCalls[1].time)
        assertEquals(SeatPresence.DISCONNECTED.name, presenceUseCase.calls.last().presence)
    }

    @Test
    fun `만료 처리가 실패해도 이미 재접속한 사용자의 감시는 다시 걸지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val connections = ConnectionTimerFakeUserConnections()
        val timer = ConnectionTimer(scheduler, clock, ConnectionTimerFakeUpdateSeatPresenceUseCase(), ConnectionTimerFailingExpireConnectionUseCase(), ConnectionTimerFakeStandUpUseCase(), tables, InMemoryJoinQueue(), ConnectionTimerFakeJoinQueueNotifier(), connections, ConnectionTimerFakeTableExecutor())

        timer.onSessionDisconnect(disconnectEvent(42L))
        connections.connectedUserIds += UserId(42L)
        scheduler.scheduledCalls[0].task.run()

        assertEquals(1, scheduler.scheduledCalls.size)
    }

    @Test
    fun `예약된 퇴장 하나가 실패해도 나머지는 실행되고 실패한 사용자는 감시를 다시 건다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val tableId = TableId(7L)
        val standUps = mutableListOf<Long>()
        val standUpUseCase = object : StandUpUseCase {
            override fun standUp(command: StandUpCommand) {
                if (command.userId == 42L) throw IllegalStateException("db down")
                standUps += command.userId
            }
        }
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = tableId)
        val timer = ConnectionTimer(scheduler, clock, ConnectionTimerFakeUpdateSeatPresenceUseCase(), expireUseCase, standUpUseCase, tables, InMemoryJoinQueue(), ConnectionTimerFakeJoinQueueNotifier(), ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        for (userId in listOf(42L, 43L)) {
            timer.onSessionDisconnect(disconnectEvent(userId))
        }
        scheduler.scheduledCalls.toList().forEach { it.task.run() } // 두 명 모두 핸드 중 예약만 남는다
        val before = scheduler.scheduledCalls.size

        timer.onHandBroadcastRequested(HandBroadcastRequested(tableId, HoldemTable.create("t"), finishedHand()))

        assertEquals(listOf(43L), standUps)
        assertEquals(before + 1, scheduler.scheduledCalls.size)
    }

    @Test
    fun `presence 갱신이 던져도 만료 타이머는 먼저 걸려 있고 예외는 그대로 전파된다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val failingPresence = object : UpdateSeatPresenceUseCase {
            override fun update(command: UpdateSeatPresenceCommand) = throw IllegalStateException("db down")
        }
        val timer = ConnectionTimer(scheduler, clock, failingPresence, ConnectionTimerFakeExpireConnectionUseCase(null), ConnectionTimerFakeStandUpUseCase(), tables, InMemoryJoinQueue(), ConnectionTimerFakeJoinQueueNotifier(), ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())

        assertFailsWith<IllegalStateException> { timer.beginWatch(UserId(42L)) }

        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(Duration.ofMinutes(3)), scheduler.scheduledCalls[0].time)
    }

    @Test
    fun `닫힌 테이블의 퇴장 예약은 핸드가 끝나도 실행되지 않는다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val tableId = TableId(7L)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val timer = ConnectionTimer(scheduler, clock, ConnectionTimerFakeUpdateSeatPresenceUseCase(), ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = tableId), standUpUseCase, tables, InMemoryJoinQueue(), ConnectionTimerFakeJoinQueueNotifier(), ConnectionTimerFakeUserConnections(), ConnectionTimerFakeTableExecutor())
        timer.onSessionDisconnect(disconnectEvent(42L))
        scheduler.scheduledCalls[0].task.run()

        timer.onTableClosed(TableClosed(tableId))
        timer.onHandBroadcastRequested(HandBroadcastRequested(tableId, HoldemTable.create("t"), finishedHand()))

        assertEquals(0, standUpUseCase.calls.size)
    }
}
