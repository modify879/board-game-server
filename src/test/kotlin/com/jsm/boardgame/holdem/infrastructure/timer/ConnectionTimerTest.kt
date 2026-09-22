package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.recovery.HandRecovery
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

private class ConnectionTimerFakeStandUpUseCase : StandUpUseCase {
    val calls = mutableListOf<StandUpCommand>()
    override fun standUp(command: StandUpCommand) {
        calls += command
    }
}

private class ConnectionTimerFakePrincipal(private val id: Long) : Principal {
    override fun getName(): String = id.toString()
}

/** [HandRecovery] 를 구성하는 데 필요한 사소한 의존성들. 대부분의 테스트는 이 store 들이 비어 있어도 된다. */
private class ConnectionTimerFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    fun put(tableId: TableId, hand: Hand) { store[tableId.value] = hand }

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

private class ConnectionTimerFakeHoldemTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()

    fun put(table: HoldemTable) { store[table.id!!.value] = table }

    override fun findById(id: TableId): HoldemTable? = store[id.value]
    override fun findByUserId(userId: Long): HoldemTable? = store.values.firstOrNull { it.seatOf(userId) != null }
    override fun save(table: HoldemTable): HoldemTable {
        store[table.id!!.value] = table
        return table
    }
}

private class ConnectionTimerFakeResumeHandUseCase : ResumeHandUseCase {
    override fun resume(command: ResumeHandCommand) {}
}

private class ConnectionTimerFakeCancelHandUseCase : CancelHandUseCase {
    override fun cancel(command: CancelHandCommand) {}
}

class ConnectionTimerTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val identityShuffler = Shuffler { it }
    private val fakeMessage = MessageBuilder.withPayload(ByteArray(0)).build()

    // 복구 유예 중인 테이블이 없는 한 언제나 isAwaitingRecovery == false 인 no-op 인스턴스.
    // onApplicationReady() 를 부르지 않으므로 store 가 비어 있어도 충분하다.
    private val handRecovery = HandRecovery(
        ConnectionTimerFakeHandStore(),
        ConnectionTimerFakeHoldemTableRepository(),
        ConnectionTimerFakeTaskScheduler(),
        clock,
        ConnectionTimerFakeResumeHandUseCase(),
        ConnectionTimerFakeCancelHandUseCase(),
    )

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
        table.sitDown(1, 1000L, Chips.of(10_000))
        table.sitDown(2, 2000L, Chips.of(10_000))
        table.moveButtonToNextOccupiedSeat()
        return Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            table.buttonSeatNo!!,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
    }

    /** 8000 바이인에 핸드 스택만 100 으로 줘서 블라인드만으로 양쪽이 곧장 올인되게 만든다(기존 HoldemViewAssemblerTest 와 동일한 트릭). */
    private fun finishedHand(): Hand {
        val table = HoldemTable.create("test-table")
        table.sitDown(1, 1000L, Chips.of(8_000))
        table.sitDown(2, 2000L, Chips.of(8_000))
        table.moveButtonToNextOccupiedSeat()
        return Hand.start(
            mapOf(1 to Chips.of(100), 2 to Chips.of(100)),
            table.buttonSeatNo!!,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
    }

    @Test
    fun `연결이 끊기면 DISCONNECTED 로 표시하고 3분 뒤로 만료를 예약한다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

        timer.onSessionDisconnect(disconnectEvent(42L))

        assertEquals(1, presenceUseCase.calls.size)
        assertEquals(42L, presenceUseCase.calls[0].userId)
        assertEquals(SeatPresence.DISCONNECTED.name, presenceUseCase.calls[0].presence)
        assertEquals(1, scheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(Duration.ofMinutes(3)), scheduler.scheduledCalls[0].time)
    }

    @Test
    fun `재접속하면 예약이 취소되고 SEATED 로 돌아온다`() {
        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

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
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

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
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

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
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

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
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

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
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

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
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, handRecovery)

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
    fun `복구 유예 중인 사용자는 연결 타이머를 건너뛴다`() {
        val recoveryHandStore = ConnectionTimerFakeHandStore()
        val recoveryTables = ConnectionTimerFakeHoldemTableRepository()
        val recoveryScheduler = ConnectionTimerFakeTaskScheduler()
        val recovery = HandRecovery(
            recoveryHandStore,
            recoveryTables,
            recoveryScheduler,
            clock,
            ConnectionTimerFakeResumeHandUseCase(),
            ConnectionTimerFakeCancelHandUseCase(),
        )
        val table = HoldemTable.create("recovering-table")
        table.sitDown(1, 42L, Chips.of(10_000))
        table.sitDown(2, 43L, Chips.of(10_000))
        table.moveButtonToNextOccupiedSeat()
        val recoveringTable = HoldemTable.reconstitute(
            id = TableId(7L),
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            version = table.version,
        )
        recoveryTables.put(recoveringTable)
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            recoveringTable.buttonSeatNo!!,
            recoveringTable.smallBlind,
            recoveringTable.bigBlind,
            identityShuffler,
        )
        recoveryHandStore.put(TableId(7L), hand)
        recovery.onApplicationReady()

        val scheduler = ConnectionTimerFakeTaskScheduler()
        val presenceUseCase = ConnectionTimerFakeUpdateSeatPresenceUseCase()
        val expireUseCase = ConnectionTimerFakeExpireConnectionUseCase(reservedTableId = null)
        val standUpUseCase = ConnectionTimerFakeStandUpUseCase()
        val timer = ConnectionTimer(scheduler, clock, presenceUseCase, expireUseCase, standUpUseCase, recovery)

        timer.onSessionDisconnect(disconnectEvent(42L))

        assertEquals(0, presenceUseCase.calls.size)
        assertEquals(0, scheduler.scheduledCalls.size)
    }
}
