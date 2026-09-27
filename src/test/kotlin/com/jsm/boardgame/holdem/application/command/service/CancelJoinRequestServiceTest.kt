package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.exception.JoinRequestNotFoundException
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class CancelJoinRequestFakeJoinQueueNotifier : JoinQueueNotifier {
    val positionsCalls = mutableListOf<Pair<TableId, List<JoinQueueEntry>>>()

    override fun notifySeated(tableId: TableId, userId: Long, seatNo: Int) {}

    override fun notifyDropped(tableId: TableId, userId: Long, errorCode: ErrorCode) {}

    override fun notifyPositions(tableId: TableId, entries: List<JoinQueueEntry>) {
        positionsCalls += tableId to entries
    }
}

class CancelJoinRequestServiceTest {

    @Test
    fun `취소하면 대기 중인 항목이 대기열에서 제거되고 남은 사람들의 순번이 알려진다`() {
        val queue = InMemoryJoinQueue()
        val notifier = CancelJoinRequestFakeJoinQueueNotifier()
        val service = CancelJoinRequestService(queue, notifier)
        val tableId = TableId(1L)
        queue.enqueue(tableId, userId = 1L, buyIn = Chips.of(10_000), postBlindImmediately = false)
        queue.enqueue(tableId, userId = 2L, buyIn = Chips.of(10_000), postBlindImmediately = false)

        service.cancel(CancelJoinRequestCommand(tableId.value, userId = 1L))

        assertTrue(!queue.isQueued(1L))
        assertEquals(listOf(2L), notifier.positionsCalls.last().second.map { it.userId })
    }

    @Test
    fun `대기 중인 요청이 없으면 JOIN_REQUEST_NOT_FOUND 다`() {
        val queue = InMemoryJoinQueue()
        val notifier = CancelJoinRequestFakeJoinQueueNotifier()
        val service = CancelJoinRequestService(queue, notifier)

        val e = assertFailsWith<JoinRequestNotFoundException> {
            service.cancel(CancelJoinRequestCommand(999L, userId = 1L))
        }
        assertEquals(HoldemErrorCode.JOIN_REQUEST_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `다른 사용자의 취소는 내 대기열 항목에 영향을 주지 않는다`() {
        val queue = InMemoryJoinQueue()
        val notifier = CancelJoinRequestFakeJoinQueueNotifier()
        val service = CancelJoinRequestService(queue, notifier)
        val tableId = TableId(1L)
        queue.enqueue(tableId, userId = 1L, buyIn = Chips.of(10_000), postBlindImmediately = false)
        queue.enqueue(tableId, userId = 2L, buyIn = Chips.of(10_000), postBlindImmediately = false)

        service.cancel(CancelJoinRequestCommand(tableId.value, userId = 2L))

        assertTrue(queue.isQueued(1L))
        assertEquals(1L, queue.peekHead(tableId)?.userId)
    }
}
