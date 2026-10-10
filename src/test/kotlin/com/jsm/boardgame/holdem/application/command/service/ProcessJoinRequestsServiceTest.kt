package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.common.error.CommonErrorCode
import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestResult
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsCommand
import com.jsm.boardgame.holdem.application.exception.JoinRequestNotFoundException
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.infrastructure.executor.SerialTableExecutor
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** userId 당 하나의 스크립트를 등록한다 — 이 서비스는 userId 마다 admit 을 한 번만 부른다
 *  (성공하면 대기열에서 빠지고, 실패해도 버려지거나 루프가 멈춘다). */
// 실행기가 테이블 존재만 확인하므로 모든 테이블이 있다고 답한다.
private class ProcessJoinRequestsFakeHoldemTableRepository : HoldemTableRepository {
    override fun findById(id: TableId): HoldemTable? = HoldemTable.create("t")
    override fun findByUserId(userId: UserId): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<UserId> = emptyList()
    override fun findAllTableIds(): List<TableId> = emptyList()
    override fun save(table: HoldemTable): HoldemTable = table
}

private class ProcessJoinRequestsFakeAdmitUseCase(
    private val scripts: Map<Long, () -> AdmitJoinRequestResult>,
) : AdmitJoinRequestUseCase {
    val calls = mutableListOf<AdmitJoinRequestCommand>()
    override fun admit(command: AdmitJoinRequestCommand): AdmitJoinRequestResult {
        calls += command
        val script = scripts[command.userId] ?: error("스크립트가 없는 userId: ${command.userId}")
        return script()
    }
}

private class ProcessJoinRequestsFakeUserConnections(private val disconnected: Set<Long> = emptySet()) : UserConnections {
    override fun isConnected(userId: UserId): Boolean = userId.value !in disconnected
    override fun hasOtherSession(userId: UserId, excludingSessionId: String): Boolean = false
}

private class ProcessJoinRequestsFakeJoinQueueNotifier : JoinQueueNotifier {
    data class Seated(val tableId: TableId, val userId: Long, val seatNo: Int)
    data class Dropped(val tableId: TableId, val userId: Long, val errorCode: ErrorCode)

    val seatedCalls = mutableListOf<Seated>()
    val droppedCalls = mutableListOf<Dropped>()
    val positionsCalls = mutableListOf<Pair<TableId, List<JoinQueueEntry>>>()

    override fun notifySeated(tableId: TableId, userId: UserId, seatNo: Int) {
        seatedCalls += Seated(tableId, userId.value, seatNo)
    }

    override fun notifyDropped(tableId: TableId, userId: UserId, errorCode: ErrorCode) {
        droppedCalls += Dropped(tableId, userId.value, errorCode)
    }

    override fun notifyPositions(tableId: TableId, entries: List<JoinQueueEntry>) {
        positionsCalls += tableId to entries
    }
}

class ProcessJoinRequestsServiceTest {

    private val tableId = TableId(1)

    @Test
    fun `연결이 없는 대기열 항목은 앉히지 않고 NOT_CONNECTED 로 버린 뒤 다음 항목을 처리한다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, UserId(101L), false)
        queue.enqueue(tableId, UserId(102L), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(mapOf(102L to { AdmitJoinRequestResult.Seated(1) }))
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier, ProcessJoinRequestsFakeUserConnections(disconnected = setOf(101L)))

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertEquals(listOf(102L), admit.calls.map { it.userId })
        assertFalse(queue.isQueued(UserId(101L)))
        assertEquals(
            listOf(ProcessJoinRequestsFakeJoinQueueNotifier.Dropped(tableId, 101L, HoldemErrorCode.NOT_CONNECTED)),
            notifier.droppedCalls,
        )
    }

    @Test
    fun `대기열 맨 앞부터 순서대로 좌석에 앉히고 완료되면 대기열이 빈다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, UserId(101L), false)
        queue.enqueue(tableId, UserId(102L), false)
        queue.enqueue(tableId, UserId(103L), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(
            mapOf(
                101L to { AdmitJoinRequestResult.Seated(1) },
                102L to { AdmitJoinRequestResult.Seated(2) },
                103L to { AdmitJoinRequestResult.Seated(3) },
            ),
        )
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier, ProcessJoinRequestsFakeUserConnections())

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertEquals(listOf(101L, 102L, 103L), admit.calls.map { it.userId })
        assertNull(queue.peekHead(tableId))
        assertEquals(
            listOf(
                ProcessJoinRequestsFakeJoinQueueNotifier.Seated(tableId, 101L, 1),
                ProcessJoinRequestsFakeJoinQueueNotifier.Seated(tableId, 102L, 2),
                ProcessJoinRequestsFakeJoinQueueNotifier.Seated(tableId, 103L, 3),
            ),
            notifier.seatedCalls,
        )
        assertEquals(3, notifier.positionsCalls.size)
        assertEquals(emptyList(), notifier.positionsCalls.last().second)
    }

    @Test
    fun `핸드가 진행 중이면 대기열을 그대로 두고 처리를 멈춘다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, UserId(101L), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(mapOf(101L to { AdmitJoinRequestResult.Blocked }))
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier, ProcessJoinRequestsFakeUserConnections())

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertEquals(UserId(101L), queue.peekHead(tableId)?.userId)
        assertTrue(notifier.seatedCalls.isEmpty())
        assertTrue(notifier.droppedCalls.isEmpty())
    }

    // 테이블이 가득 찬 경우도 AdmitJoinRequestUseCase 가 같은 Blocked 를 돌려주므로
    // 서비스 입장에서는 위 테스트와 동일한 경로다 — 별도 테스트를 추가하지 않는다.

    @Test
    fun `지갑 실패로 앞사람이 버려져도 다음 사람은 계속 처리된다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, UserId(101L), false)
        queue.enqueue(tableId, UserId(102L), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(
            mapOf(
                101L to { throw BuyInOutOfRangeException("wallet failure simulation") },
                102L to { AdmitJoinRequestResult.Seated(1) },
            ),
        )
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier, ProcessJoinRequestsFakeUserConnections())

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertFalse(queue.isQueued(UserId(101L)))
        assertEquals(
            listOf(ProcessJoinRequestsFakeJoinQueueNotifier.Dropped(tableId, 101L, HoldemErrorCode.BUY_IN_OUT_OF_RANGE)),
            notifier.droppedCalls,
        )
        assertEquals(
            listOf(ProcessJoinRequestsFakeJoinQueueNotifier.Seated(tableId, 102L, 1)),
            notifier.seatedCalls,
        )
        assertNull(queue.peekHead(tableId))
    }

    @Test
    fun `비즈니스 예외가 아닌 오류로 앞사람이 실패해도 버려지고 TEMPORARILY_UNAVAILABLE 로 알리며 다음 사람은 처리된다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, UserId(101L), false)
        queue.enqueue(tableId, UserId(102L), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(
            mapOf(
                101L to { throw RuntimeException("lock timeout simulation") },
                102L to { AdmitJoinRequestResult.Seated(1) },
            ),
        )
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier, ProcessJoinRequestsFakeUserConnections())

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertFalse(queue.isQueued(UserId(101L)))
        assertEquals(
            listOf(ProcessJoinRequestsFakeJoinQueueNotifier.Dropped(tableId, 101L, CommonErrorCode.TEMPORARILY_UNAVAILABLE)),
            notifier.droppedCalls,
        )
        assertEquals(
            listOf(ProcessJoinRequestsFakeJoinQueueNotifier.Seated(tableId, 102L, 1)),
            notifier.seatedCalls,
        )
        assertNull(queue.peekHead(tableId))
    }

    @Test
    fun `착석 시도가 인터럽트되면 머리를 버리지 않고 인터럽트 플래그를 세운 채 멈춘다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, UserId(101L), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(mapOf(101L to { throw InterruptedException("shutdown") }))
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier, ProcessJoinRequestsFakeUserConnections())

        try {
            service.process(ProcessJoinRequestsCommand(tableId.value))

            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // 다른 테스트로 새지 않게 플래그를 지운다
        }
        assertEquals(UserId(101L), queue.peekHead(tableId)?.userId)
        assertTrue(notifier.droppedCalls.isEmpty())
    }

    @Test
    fun `착석 처리 중에는 취소가 기다리다가 결국 착석되고 취소는 JOIN_REQUEST_NOT_FOUND 로 실패한다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, UserId(101L), false)
        val admitStarted = CountDownLatch(1)
        val releaseAdmit = CountDownLatch(1)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(
            mapOf(
                101L to {
                    admitStarted.countDown()
                    releaseAdmit.await(5, TimeUnit.SECONDS)
                    AdmitJoinRequestResult.Seated(1)
                },
            ),
        )
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier, ProcessJoinRequestsFakeUserConnections())
        val cancelNotifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val tableExecutor = SerialTableExecutor(ProcessJoinRequestsFakeHoldemTableRepository())
        val cancelService = CancelJoinRequestService(queue, cancelNotifier, tableExecutor)

        val processThread = thread {
            tableExecutor.call(tableId) { service.process(ProcessJoinRequestsCommand(tableId.value)) }
        }
        assertTrue(admitStarted.await(5, TimeUnit.SECONDS))

        var cancelFailure: Throwable? = null
        val cancelThread = thread {
            try {
                cancelService.cancel(CancelJoinRequestCommand(tableId.value, userId = 101L))
            } catch (e: Throwable) {
                cancelFailure = e
            }
        }
        Thread.sleep(200)
        releaseAdmit.countDown()

        processThread.join(5_000)
        cancelThread.join(5_000)

        assertEquals(
            listOf(ProcessJoinRequestsFakeJoinQueueNotifier.Seated(tableId, 101L, 1)),
            notifier.seatedCalls,
        )
        assertTrue(cancelFailure is JoinRequestNotFoundException)
        assertEquals(HoldemErrorCode.JOIN_REQUEST_NOT_FOUND, (cancelFailure as JoinRequestNotFoundException).errorCode)
    }
}
