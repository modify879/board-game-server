package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.exception.JoinRequestNotFoundException
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class CancelJoinRequestFakeJoinQueueNotifier : JoinQueueNotifier {
    val positionsCalls = mutableListOf<Pair<TableId, List<JoinQueueEntry>>>()

    override fun notifySeated(tableId: TableId, userId: UserId, seatNo: Int) {}

    override fun notifyDropped(tableId: TableId, userId: UserId, errorCode: ErrorCode) {}

    override fun notifyPositions(tableId: TableId, entries: List<JoinQueueEntry>) {
        positionsCalls += tableId to entries
    }
}

/** 이 파일의 테스트는 전부 단일 스레드 순차 호출이라 실제 직렬화가 필요 없다 — 인라인으로 실행한다. */
private class CancelJoinRequestFakeTableExecutor : TableExecutor {
    override fun <T> call(tableId: TableId, task: () -> T): T = task()
    override fun post(tableId: TableId, task: () -> Unit) = task()
}

class CancelJoinRequestServiceTest {

    @Test
    fun `취소하면 대기 중인 항목이 대기열에서 제거되고 남은 사람들의 순번이 알려진다`() {
        val queue = InMemoryJoinQueue(50)
        val notifier = CancelJoinRequestFakeJoinQueueNotifier()
        val service = CancelJoinRequestService(queue, notifier, CancelJoinRequestFakeTableExecutor())
        val tableId = TableId(1L)
        queue.enqueue(tableId, userId = UserId(1L), postBlindImmediately = false, buyIn = Chips.of(8_000))
        queue.enqueue(tableId, userId = UserId(2L), postBlindImmediately = false, buyIn = Chips.of(8_000))

        service.cancel(CancelJoinRequestCommand(userId = 1L))

        assertTrue(!queue.isQueued(UserId(1L)))
        assertEquals(listOf(UserId(2L)), notifier.positionsCalls.last().second.map { it.userId })
    }

    @Test
    fun `대기 중인 요청이 없으면 JOIN_REQUEST_NOT_FOUND 다`() {
        val queue = InMemoryJoinQueue(50)
        val notifier = CancelJoinRequestFakeJoinQueueNotifier()
        val service = CancelJoinRequestService(queue, notifier, CancelJoinRequestFakeTableExecutor())

        val e = assertFailsWith<JoinRequestNotFoundException> {
            service.cancel(CancelJoinRequestCommand(userId = 1L))
        }
        assertEquals(HoldemErrorCode.JOIN_REQUEST_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `다른 사용자의 취소는 내 대기열 항목에 영향을 주지 않는다`() {
        val queue = InMemoryJoinQueue(50)
        val notifier = CancelJoinRequestFakeJoinQueueNotifier()
        val service = CancelJoinRequestService(queue, notifier, CancelJoinRequestFakeTableExecutor())
        val tableId = TableId(1L)
        queue.enqueue(tableId, userId = UserId(1L), postBlindImmediately = false, buyIn = Chips.of(8_000))
        queue.enqueue(tableId, userId = UserId(2L), postBlindImmediately = false, buyIn = Chips.of(8_000))

        service.cancel(CancelJoinRequestCommand(userId = 2L))

        assertTrue(queue.isQueued(UserId(1L)))
        assertEquals(UserId(1L), queue.peekHead(tableId)?.userId)
    }

    @Test
    fun `착석 처리로 이미 제거된 사용자의 취소는 JOIN_REQUEST_NOT_FOUND 다`() {
        val queue = InMemoryJoinQueue(50)
        val notifier = CancelJoinRequestFakeJoinQueueNotifier()
        val service = CancelJoinRequestService(queue, notifier, CancelJoinRequestFakeTableExecutor())
        val tableId = TableId(1L)
        queue.enqueue(tableId, userId = UserId(1L), postBlindImmediately = false, buyIn = Chips.of(8_000))
        queue.removeByUserId(UserId(1L))

        val e = assertFailsWith<JoinRequestNotFoundException> {
            service.cancel(CancelJoinRequestCommand(userId = 1L))
        }
        assertEquals(HoldemErrorCode.JOIN_REQUEST_NOT_FOUND, e.errorCode)
    }
}
