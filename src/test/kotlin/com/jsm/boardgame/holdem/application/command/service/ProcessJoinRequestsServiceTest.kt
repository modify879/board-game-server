package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestResult
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsCommand
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** userId 당 하나의 스크립트를 등록한다 — 이 서비스는 userId 마다 admit 을 한 번만 부른다
 *  (성공하면 대기열에서 빠지고, 실패해도 버려지거나 루프가 멈춘다). */
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

private class ProcessJoinRequestsFakeJoinQueueNotifier : JoinQueueNotifier {
    data class Seated(val tableId: TableId, val userId: Long, val seatNo: Int)
    data class Dropped(val tableId: TableId, val userId: Long, val errorCode: ErrorCode)

    val seatedCalls = mutableListOf<Seated>()
    val droppedCalls = mutableListOf<Dropped>()
    val positionsCalls = mutableListOf<Pair<TableId, List<JoinQueueEntry>>>()

    override fun notifySeated(tableId: TableId, userId: Long, seatNo: Int) {
        seatedCalls += Seated(tableId, userId, seatNo)
    }

    override fun notifyDropped(tableId: TableId, userId: Long, errorCode: ErrorCode) {
        droppedCalls += Dropped(tableId, userId, errorCode)
    }

    override fun notifyPositions(tableId: TableId, entries: List<JoinQueueEntry>) {
        positionsCalls += tableId to entries
    }
}

class ProcessJoinRequestsServiceTest {

    private val tableId = TableId(1)

    @Test
    fun `대기열 맨 앞부터 순서대로 좌석에 앉히고 완료되면 대기열이 빈다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, 101L, Chips.of(8_000), false)
        queue.enqueue(tableId, 102L, Chips.of(8_000), false)
        queue.enqueue(tableId, 103L, Chips.of(8_000), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(
            mapOf(
                101L to { AdmitJoinRequestResult.Seated(1) },
                102L to { AdmitJoinRequestResult.Seated(2) },
                103L to { AdmitJoinRequestResult.Seated(3) },
            ),
        )
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier)

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
        queue.enqueue(tableId, 101L, Chips.of(8_000), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(mapOf(101L to { AdmitJoinRequestResult.Blocked }))
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier)

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertEquals(101L, queue.peekHead(tableId)?.userId)
        assertTrue(notifier.seatedCalls.isEmpty())
        assertTrue(notifier.droppedCalls.isEmpty())
    }

    // 테이블이 가득 찬 경우도 AdmitJoinRequestUseCase 가 같은 Blocked 를 돌려주므로
    // 서비스 입장에서는 위 테스트와 동일한 경로다 — 별도 테스트를 추가하지 않는다.

    @Test
    fun `지갑 실패로 앞사람이 버려져도 다음 사람은 계속 처리된다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, 101L, Chips.of(8_000), false)
        queue.enqueue(tableId, 102L, Chips.of(8_000), false)
        val admit = ProcessJoinRequestsFakeAdmitUseCase(
            mapOf(
                101L to { throw BuyInOutOfRangeException("wallet failure simulation") },
                102L to { AdmitJoinRequestResult.Seated(1) },
            ),
        )
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier)

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertFalse(queue.isQueued(101L))
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
    fun `경합 예외가 나면 같은 head 를 다시 시도해 결국 착석시킨다`() {
        val queue = InMemoryJoinQueue()
        queue.enqueue(tableId, 101L, Chips.of(8_000), false)
        var attempts = 0
        val admit = ProcessJoinRequestsFakeAdmitUseCase(
            mapOf(
                101L to {
                    attempts++
                    if (attempts == 1) throw ConcurrentTableUpdateException("competing update")
                    AdmitJoinRequestResult.Seated(1)
                },
            ),
        )
        val notifier = ProcessJoinRequestsFakeJoinQueueNotifier()
        val service = ProcessJoinRequestsService(queue, admit, notifier)

        service.process(ProcessJoinRequestsCommand(tableId.value))

        assertEquals(listOf(101L, 101L), admit.calls.map { it.userId })
        assertEquals(
            listOf(ProcessJoinRequestsFakeJoinQueueNotifier.Seated(tableId, 101L, 1)),
            notifier.seatedCalls,
        )
        assertTrue(notifier.droppedCalls.isEmpty())
        assertNull(queue.peekHead(tableId))
    }
}
