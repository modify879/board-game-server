package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutUseCase
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import com.jsm.boardgame.holdem.infrastructure.recovery.HandRecovery
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 호출되지 않는 협력자 자리 — 이 테스트가 보는 경로는 이 인터페이스들을 건드리지 않는다. */
private inline fun <reified T : Any> closedCleanerUnused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
        throw UnsupportedOperationException()
    } as T

private class ClosedCleanerFakeTableExecutor : TableExecutor {
    val removed = mutableListOf<TableId>()
    override fun <T> call(tableId: TableId, task: () -> T): T = task()
    override fun post(tableId: TableId, task: () -> Unit) = task()
    override fun remove(tableId: TableId) { removed += tableId }
}

class ClosedTableCleanerTest {

    private val clock = Clock.systemUTC()
    private val scheduler = ThreadPoolTaskScheduler().apply { initialize() }
    private val executor = ClosedCleanerFakeTableExecutor()
    private val joinQueue = InMemoryJoinQueue()
    private val tableId = TableId(1L)

    private val turnTimer = TurnTimer(scheduler, clock, closedCleanerUnused<ExpireTurnUseCase>(), executor, closedCleanerUnused<CancelHandUseCase>())
    private val nextHandTimer = NextHandTimer(scheduler, closedCleanerUnused<StartScheduledHandUseCase>(), executor, clock, Duration.ofMinutes(10))
    private val runoutTimer = RunoutTimer(scheduler, clock, closedCleanerUnused<AdvanceRunoutUseCase>(), closedCleanerUnused<CancelHandUseCase>(), executor, Duration.ofMinutes(10))
    private val connectionTimer = ConnectionTimer(
        scheduler, clock, closedCleanerUnused(), closedCleanerUnused(), closedCleanerUnused(), closedCleanerUnused(),
        joinQueue, closedCleanerUnused(), closedCleanerUnused(), executor,
    )
    private val handRecovery = HandRecovery(
        closedCleanerUnused(), closedCleanerUnused(), scheduler, clock, closedCleanerUnused(), closedCleanerUnused(),
        connectionTimer, nextHandTimer, executor,
    )
    private val cleaner = ClosedTableCleaner(executor, joinQueue, turnTimer, nextHandTimer, runoutTimer, connectionTimer, handRecovery)

    @Test
    fun `테이블이 닫히면 대기열과 타이머 예약과 실행기를 모두 치운다`() {
        val hand = Hand.start(mapOf(1 to Chips.of(1_000), 2 to Chips.of(1_000)), 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it })
        TransactionSynchronizationManager.initSynchronization()
        try {
            turnTimer.onHandBroadcastRequested(HandBroadcastRequested(tableId, HoldemTable.create("t"), hand))
            TransactionSynchronizationUtils.triggerAfterCommit()
        } finally {
            TransactionSynchronizationManager.clear()
        }
        nextHandTimer.restart(tableId)
        joinQueue.enqueue(tableId, 9L, false)

        cleaner.onTableClosed(TableClosed(tableId))

        assertNull(turnTimer.remaining(tableId))
        assertNull(nextHandTimer.remaining(tableId))
        assertNull(joinQueue.tableOf(9L))
        assertEquals(listOf(tableId), executor.removed)
    }
}
