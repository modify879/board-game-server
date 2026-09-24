package com.jsm.boardgame.holdem.infrastructure.recovery

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.timer.ConnectionTimer
import com.jsm.boardgame.holdem.infrastructure.timer.NextHandTimer
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.web.socket.messaging.SessionConnectedEvent
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

private class HandRecoveryFakeScheduledFuture : ScheduledFuture<Any?> {
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
private class HandRecoveryFakeTaskScheduler : TaskScheduler {
    class Scheduled(val task: Runnable, val time: Instant, val future: HandRecoveryFakeScheduledFuture)

    val scheduledCalls = mutableListOf<Scheduled>()

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = HandRecoveryFakeScheduledFuture()
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

private class HandRecoveryFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    fun put(tableId: TableId, hand: Hand) { store[tableId.value] = hand }

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

private class HandRecoveryFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]
    override fun findByUserId(userId: Long): HoldemTable? = store.values.firstOrNull { it.seatOf(userId) != null }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null

    override fun save(table: HoldemTable): HoldemTable {
        if (table.id != null) {
            store[table.id.value] = table
            return table
        }
        val id = TableId(nextId++)
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            version = table.version,
            nextHandAt = table.nextHandAt,
        )
        store[id.value] = saved
        return saved
    }

    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }
}

private class HandRecoveryFakeUpdateSeatPresenceUseCase : UpdateSeatPresenceUseCase {
    val calls = mutableListOf<UpdateSeatPresenceCommand>()
    override fun update(command: UpdateSeatPresenceCommand) {
        calls += command
    }
}

private class HandRecoveryFakeExpireConnectionUseCase(
    private val reservedTableId: TableId?,
) : ExpireConnectionUseCase {
    override fun expire(command: ExpireConnectionCommand): TableId? = reservedTableId
}

private class HandRecoveryFakeStandUpUseCase : StandUpUseCase {
    override fun standUp(command: StandUpCommand) {}
}

private class HandRecoveryFakeResumeHandUseCase : ResumeHandUseCase {
    val calls = mutableListOf<ResumeHandCommand>()
    override fun resume(command: ResumeHandCommand) { calls += command }
}

private class HandRecoveryFakeCancelHandUseCase : CancelHandUseCase {
    val calls = mutableListOf<CancelHandCommand>()
    override fun cancel(command: CancelHandCommand) { calls += command }
}

private class HandRecoveryFakeStartScheduledHandUseCase : StartScheduledHandUseCase {
    val calls = mutableListOf<StartScheduledHandCommand>()
    override fun start(command: StartScheduledHandCommand) {
        calls += command
    }
}

private class HandRecoveryFakePrincipal(private val id: Long) : Principal {
    override fun getName(): String = id.toString()
}

class HandRecoveryTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val identityShuffler = Shuffler { it }
    private val fakeMessage = MessageBuilder.withPayload(ByteArray(0)).build()

    private fun connectedEvent(userId: Long) =
        SessionConnectedEvent(this, fakeMessage, HandRecoveryFakePrincipal(userId))

    private fun newConnectionTimer(tables: HoldemTableRepository): ConnectionTimer = ConnectionTimer(
        HandRecoveryFakeTaskScheduler(),
        clock,
        HandRecoveryFakeUpdateSeatPresenceUseCase(),
        HandRecoveryFakeExpireConnectionUseCase(reservedTableId = null),
        HandRecoveryFakeStandUpUseCase(),
        tables,
    )

    /** userId = seatNo * 1000L 로 좌석을 채운 테이블을 만들고 참가시켜 진행 중 핸드를 만든다. */
    private fun seatTableWithHand(tables: HandRecoveryFakeTableRepository, tableId: Long, vararg seatNos: Int): Hand {
        var table = HoldemTable.create("test-table")
        table = HoldemTable.reconstitute(
            id = TableId(tableId),
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = null,
            seats = emptyMap(),
            version = 0,
        )
        for (seatNo in seatNos) {
            table.sitDown(seatNo, userId = seatNo * 1000L, buyIn = Chips.of(10_000))
        }
        tables.save(table)
        table.moveButtonToNextOccupiedSeat()
        val stacks = seatNos.associateWith { Chips.of(10_000) }
        return Hand.start(
            stacks = stacks,
            buttonSeatNo = table.buttonSeatNo!!,
            smallBlindSeatNo = table.buttonSeatNo,
            bigBlindSeatNo = stacks.keys.single { it != table.buttonSeatNo },
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            shuffler = identityShuffler,
        )
    }

    @Test
    fun `복구할 진행 중 핸드가 없으면 아무 것도 예약하지 않는다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val scheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()
        val recovery = HandRecovery(handStore, tables, scheduler, clock, resumeUseCase, cancelUseCase, newConnectionTimer(tables), NextHandTimer(HandRecoveryFakeTaskScheduler(), HandRecoveryFakeStartScheduledHandUseCase()))

        recovery.onApplicationReady()

        assertEquals(0, scheduler.scheduledCalls.size)
        assertEquals(0, resumeUseCase.calls.size)
        assertEquals(0, cancelUseCase.calls.size)
    }

    @Test
    fun `좌석 전원이 재접속하면 핸드를 재개한다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val scheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()
        val recovery = HandRecovery(handStore, tables, scheduler, clock, resumeUseCase, cancelUseCase, newConnectionTimer(tables), NextHandTimer(HandRecoveryFakeTaskScheduler(), HandRecoveryFakeStartScheduledHandUseCase()))

        val hand = seatTableWithHand(tables, 1L, 1, 2)
        handStore.put(TableId(1L), hand)

        recovery.onApplicationReady()
        recovery.onSessionConnected(connectedEvent(1000L))
        recovery.onSessionConnected(connectedEvent(2000L))

        assertEquals(1, resumeUseCase.calls.size)
        assertEquals(1L, resumeUseCase.calls[0].tableId)
        assertEquals(0, cancelUseCase.calls.size)
        assertTrue(scheduler.scheduledCalls[0].future.cancelled)
    }

    @Test
    fun `일부만 재접속한 채 유예 시간이면 재개도 취소도 부르지 않는다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val scheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()
        val recovery = HandRecovery(handStore, tables, scheduler, clock, resumeUseCase, cancelUseCase, newConnectionTimer(tables), NextHandTimer(HandRecoveryFakeTaskScheduler(), HandRecoveryFakeStartScheduledHandUseCase()))

        val hand = seatTableWithHand(tables, 1L, 1, 2)
        handStore.put(TableId(1L), hand)

        recovery.onApplicationReady()
        recovery.onSessionConnected(connectedEvent(1000L))

        assertEquals(0, resumeUseCase.calls.size)
        assertEquals(0, cancelUseCase.calls.size)
    }

    @Test
    fun `한 명이라도 유예 시간 안에 돌아오지 않으면 핸드를 취소한다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val scheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()
        val recovery = HandRecovery(handStore, tables, scheduler, clock, resumeUseCase, cancelUseCase, newConnectionTimer(tables), NextHandTimer(HandRecoveryFakeTaskScheduler(), HandRecoveryFakeStartScheduledHandUseCase()))

        val hand = seatTableWithHand(tables, 1L, 1, 2)
        handStore.put(TableId(1L), hand)

        recovery.onApplicationReady()
        recovery.onSessionConnected(connectedEvent(1000L)) // 한 명만 복귀
        scheduler.scheduledCalls[0].task.run() // 3분 경과를 흉내낸다

        assertEquals(1, cancelUseCase.calls.size)
        assertEquals(1L, cancelUseCase.calls[0].tableId)
        assertEquals(0, resumeUseCase.calls.size)
    }

    @Test
    fun `stale 토큰으로 깨어난 만료 작업은 무시된다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val scheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()
        val recovery = HandRecovery(handStore, tables, scheduler, clock, resumeUseCase, cancelUseCase, newConnectionTimer(tables), NextHandTimer(HandRecoveryFakeTaskScheduler(), HandRecoveryFakeStartScheduledHandUseCase()))

        val hand = seatTableWithHand(tables, 1L, 1, 2)
        handStore.put(TableId(1L), hand)

        recovery.onApplicationReady()
        val stale = scheduler.scheduledCalls[0]
        recovery.onApplicationReady() // 복구 절차가 재부팅 때처럼 같은 테이블을 다시 훑는 상황을 흉내낸다
        val fresh = scheduler.scheduledCalls[1]

        stale.task.run()
        assertEquals(0, cancelUseCase.calls.size)

        fresh.task.run()
        assertEquals(1, cancelUseCase.calls.size)
    }

    @Test
    fun `복구를 시작하면 참가자들의 연결 감시를 유예시켜 부팅 스캔에서 제외한다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val scheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()

        val timerScheduler = HandRecoveryFakeTaskScheduler()
        val presenceUseCase = HandRecoveryFakeUpdateSeatPresenceUseCase()
        val connectionTimer = ConnectionTimer(
            timerScheduler,
            clock,
            presenceUseCase,
            HandRecoveryFakeExpireConnectionUseCase(reservedTableId = null),
            HandRecoveryFakeStandUpUseCase(),
            tables,
        )
        val recovery = HandRecovery(handStore, tables, scheduler, clock, resumeUseCase, cancelUseCase, connectionTimer, NextHandTimer(HandRecoveryFakeTaskScheduler(), HandRecoveryFakeStartScheduledHandUseCase()))

        val hand = seatTableWithHand(tables, 1L, 1, 2)
        handStore.put(TableId(1L), hand)

        recovery.onApplicationReady() // HandRecovery 가 먼저 실행돼(@Order(0)) 1000L/2000L 을 유예시킨다
        connectionTimer.onApplicationReady() // 그 뒤 ConnectionTimer 의 부팅 스캔이 돈다(@Order(1))

        assertEquals(0, presenceUseCase.calls.size)
    }

    @Test
    fun `복구가 끝났을 때 안 돌아온 사람에게는 연결 감시가 새로 걸리고 돌아온 사람에게는 걸리지 않는다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val recoveryScheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()

        val timerScheduler = HandRecoveryFakeTaskScheduler()
        val presenceUseCase = HandRecoveryFakeUpdateSeatPresenceUseCase()
        val connectionTimer = ConnectionTimer(
            timerScheduler,
            clock,
            presenceUseCase,
            HandRecoveryFakeExpireConnectionUseCase(reservedTableId = null),
            HandRecoveryFakeStandUpUseCase(),
            tables,
        )
        val recovery = HandRecovery(handStore, tables, recoveryScheduler, clock, resumeUseCase, cancelUseCase, connectionTimer, NextHandTimer(HandRecoveryFakeTaskScheduler(), HandRecoveryFakeStartScheduledHandUseCase()))

        val hand = seatTableWithHand(tables, 1L, 1, 2)
        handStore.put(TableId(1L), hand)

        recovery.onApplicationReady()
        recovery.onSessionConnected(connectedEvent(1000L)) // 1000L 만 복귀, 2000L 은 끝까지 안 돌아온다
        recoveryScheduler.scheduledCalls[0].task.run() // 유예 3분 경과 -> 핸드 취소 + 감시 반영

        assertEquals(1, cancelUseCase.calls.size)

        // 안 돌아온 2000L: DISCONNECTED 표시 + 3분 뒤 만료가 새로 예약된다.
        assertEquals(1, presenceUseCase.calls.size)
        assertEquals(2000L, presenceUseCase.calls[0].userId)
        assertEquals(SeatPresence.DISCONNECTED.name, presenceUseCase.calls[0].presence)
        assertEquals(1, timerScheduler.scheduledCalls.size)

        // 돌아온 1000L 에게는 어떤 감시도 걸리지 않는다.
        assertTrue(presenceUseCase.calls.none { it.userId == 1000L })
    }

    @Test
    fun `부팅 시 nextHandAt 이 채워진 테이블은 자동 시작 타이머가 재무장된다`() {
        val handStore = HandRecoveryFakeHandStore()
        val tables = HandRecoveryFakeTableRepository()
        val scheduler = HandRecoveryFakeTaskScheduler()
        val resumeUseCase = HandRecoveryFakeResumeHandUseCase()
        val cancelUseCase = HandRecoveryFakeCancelHandUseCase()

        val nextHandScheduler = HandRecoveryFakeTaskScheduler()
        val startScheduledHandUseCase = HandRecoveryFakeStartScheduledHandUseCase()
        val nextHandTimer = NextHandTimer(nextHandScheduler, startScheduledHandUseCase)

        var table = HoldemTable.create("test-table")
        table = HoldemTable.reconstitute(
            id = TableId(1L),
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = null,
            seats = emptyMap(),
            version = 0,
        )
        table.scheduleNextHand(fixedInstant.plus(Duration.ofSeconds(5)))
        tables.save(table)

        val recovery = HandRecovery(handStore, tables, scheduler, clock, resumeUseCase, cancelUseCase, newConnectionTimer(tables), nextHandTimer)

        recovery.onApplicationReady()

        assertEquals(1, nextHandScheduler.scheduledCalls.size)
        assertEquals(fixedInstant.plus(Duration.ofSeconds(5)), nextHandScheduler.scheduledCalls[0].time)
    }
}
