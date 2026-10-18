package com.jsm.boardgame.holdem.infrastructure.queue

import com.jsm.boardgame.common.error.ErrorKind
import com.jsm.boardgame.holdem.application.exception.JoinQueueFullException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.QueuePosition
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryJoinQueueTest {

    @Test
    fun `같은 테이블에 순서대로 등록하면 1부터 순번이 매겨지고 entriesOf 도 그 순서를 유지한다`() {
        val queue = InMemoryJoinQueue(50)
        val tableId = TableId(1L)

        val position1 = queue.enqueue(tableId, UserId(100L), false, Chips.of(8_000))
        val position2 = queue.enqueue(tableId, UserId(200L), false, Chips.of(8_000))
        val position3 = queue.enqueue(tableId, UserId(300L), false, Chips.of(8_000))

        assertEquals(1, position1)
        assertEquals(2, position2)
        assertEquals(3, position3)
        assertEquals(listOf(UserId(100L), UserId(200L), UserId(300L)), queue.entriesOf(tableId).map { it.userId })
    }

    @Test
    fun `이미 대기열에 있는 사용자는 다른 테이블에도 등록할 수 없다`() {
        val queue = InMemoryJoinQueue(50)
        val tableA = TableId(1L)
        val tableB = TableId(2L)
        val userId = UserId(100L)

        queue.enqueue(tableA, userId, false, Chips.of(8_000))

        val exception = assertFailsWith<AlreadySeatedException> {
            queue.enqueue(tableB, userId, false, Chips.of(8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, exception.errorCode)
    }

    @Test
    fun `맨 앞을 제거하면 나머지가 한 칸씩 당겨진다`() {
        val queue = InMemoryJoinQueue(50)
        val tableId = TableId(1L)
        val userA = UserId(100L)
        val userB = UserId(200L)
        val userC = UserId(300L)

        queue.enqueue(tableId, userA, false, Chips.of(8_000))
        queue.enqueue(tableId, userB, false, Chips.of(8_000))
        queue.enqueue(tableId, userC, false, Chips.of(8_000))

        val removedTableId = queue.removeByUserId(userA)

        assertEquals(tableId, removedTableId)
        assertEquals(listOf(userB, userC), queue.entriesOf(tableId).map { it.userId })
        assertNull(queue.removeByUserId(UserId(999L)))
    }

    @Test
    fun `peekHead 는 빈 테이블에는 null 을, 존재하면 가장 먼저 등록된 항목을 돌려준다`() {
        val queue = InMemoryJoinQueue(50)
        val tableId = TableId(1L)
        val neverUsedTableId = TableId(2L)

        assertNull(queue.peekHead(neverUsedTableId))

        queue.enqueue(tableId, UserId(100L), false, Chips.of(8_000))
        queue.enqueue(tableId, UserId(200L), false, Chips.of(8_000))

        assertEquals(UserId(100L), queue.peekHead(tableId)?.userId)
    }

    @Test
    fun `isQueued 는 등록 전후와 제거 후 상태를 정확히 반영한다`() {
        val queue = InMemoryJoinQueue(50)
        val tableId = TableId(1L)
        val userId = UserId(100L)
        val neverQueuedUserId = UserId(999L)

        assertFalse(queue.isQueued(userId))

        queue.enqueue(tableId, userId, false, Chips.of(8_000))
        assertTrue(queue.isQueued(userId))
        assertFalse(queue.isQueued(neverQueuedUserId))

        queue.removeByUserId(userId)
        assertFalse(queue.isQueued(userId))
    }

    @Test
    fun `테이블이 닫히면 그 테이블의 대기열과 사용자 색인을 함께 치운다`() {
        val queue = InMemoryJoinQueue(50)
        queue.enqueue(TableId(1L), UserId(100L), false, Chips.of(8_000))
        queue.enqueue(TableId(2L), UserId(200L), false, Chips.of(8_000))

        queue.onTableClosed(TableClosed(TableId(1L)))

        assertTrue(queue.entriesOf(TableId(1L)).isEmpty())
        assertFalse(queue.isQueued(UserId(100L)))
        assertTrue(queue.isQueued(UserId(200L)))
    }

    @Test
    fun `positionOf 는 테이블과 1-based 순번을 한 번에 돌려주고 제거 후에는 null 이다`() {
        val queue = InMemoryJoinQueue(50)
        queue.enqueue(TableId(1L), UserId(100L), false, Chips.of(8_000))
        queue.enqueue(TableId(1L), UserId(200L), false, Chips.of(8_000))

        assertEquals(QueuePosition(TableId(1L), 2), queue.positionOf(UserId(200L)))
        queue.removeByUserId(UserId(100L))
        assertEquals(QueuePosition(TableId(1L), 1), queue.positionOf(UserId(200L)))
        queue.removeByUserId(UserId(200L))
        assertNull(queue.positionOf(UserId(200L)))
    }

    @Test
    fun `상한에 닿으면 JOIN_QUEUE_FULL 이고 대기열은 그대로다 - 다른 테이블은 영향 없다`() {
        val queue = InMemoryJoinQueue(2)
        val tableId = TableId(1L)
        queue.enqueue(tableId, UserId(100L), false, Chips.of(8_000))
        queue.enqueue(tableId, UserId(200L), false, Chips.of(8_000))

        val e = assertFailsWith<JoinQueueFullException> {
            queue.enqueue(tableId, UserId(300L), false, Chips.of(8_000))
        }

        assertEquals(HoldemErrorCode.JOIN_QUEUE_FULL, e.errorCode)
        assertEquals(ErrorKind.CONFLICT, e.errorCode.kind)
        assertFalse(queue.isQueued(UserId(300L)))
        assertEquals(2, queue.entriesOf(tableId).size)
        assertEquals(1, queue.enqueue(TableId(2L), UserId(300L), false, Chips.of(8_000)))
    }

    @Test
    fun `상한에 닿아도 이미 대기 중인 사용자의 재요청은 기존대로 ALREADY_SEATED 다`() {
        val queue = InMemoryJoinQueue(1)
        queue.enqueue(TableId(1L), UserId(100L), false, Chips.of(8_000))

        val e = assertFailsWith<AlreadySeatedException> {
            queue.enqueue(TableId(1L), UserId(100L), false, Chips.of(8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `대기자가 빠지면 다시 들어갈 수 있다`() {
        val queue = InMemoryJoinQueue(1)
        queue.enqueue(TableId(1L), UserId(100L), false, Chips.of(8_000))
        queue.removeByUserId(UserId(100L))

        assertEquals(1, queue.enqueue(TableId(1L), UserId(200L), false, Chips.of(8_000)))
    }
}
