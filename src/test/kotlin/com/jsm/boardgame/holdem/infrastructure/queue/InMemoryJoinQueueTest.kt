package com.jsm.boardgame.holdem.infrastructure.queue

import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryJoinQueueTest {

    @Test
    fun `같은 테이블에 순서대로 등록하면 1부터 순번이 매겨지고 entriesOf 도 그 순서를 유지한다`() {
        val queue = InMemoryJoinQueue()
        val tableId = TableId(1L)

        val position1 = queue.enqueue(tableId, 100L, Chips.of(10_000), false)
        val position2 = queue.enqueue(tableId, 200L, Chips.of(10_000), false)
        val position3 = queue.enqueue(tableId, 300L, Chips.of(10_000), false)

        assertEquals(1, position1)
        assertEquals(2, position2)
        assertEquals(3, position3)
        assertEquals(listOf(100L, 200L, 300L), queue.entriesOf(tableId).map { it.userId })
    }

    @Test
    fun `이미 대기열에 있는 사용자는 다른 테이블에도 등록할 수 없다`() {
        val queue = InMemoryJoinQueue()
        val tableA = TableId(1L)
        val tableB = TableId(2L)
        val userId = 100L

        queue.enqueue(tableA, userId, Chips.of(10_000), false)

        val exception = assertFailsWith<AlreadySeatedException> {
            queue.enqueue(tableB, userId, Chips.of(10_000), false)
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, exception.errorCode)
    }

    @Test
    fun `맨 앞을 제거하면 나머지가 한 칸씩 당겨진다`() {
        val queue = InMemoryJoinQueue()
        val tableId = TableId(1L)
        val userA = 100L
        val userB = 200L
        val userC = 300L

        queue.enqueue(tableId, userA, Chips.of(10_000), false)
        queue.enqueue(tableId, userB, Chips.of(10_000), false)
        queue.enqueue(tableId, userC, Chips.of(10_000), false)

        val removedTableId = queue.removeByUserId(userA)

        assertEquals(tableId, removedTableId)
        assertEquals(listOf(userB, userC), queue.entriesOf(tableId).map { it.userId })
        assertNull(queue.removeByUserId(999L))
    }

    @Test
    fun `peekHead 는 빈 테이블에는 null 을, 존재하면 가장 먼저 등록된 항목을 돌려준다`() {
        val queue = InMemoryJoinQueue()
        val tableId = TableId(1L)
        val neverUsedTableId = TableId(2L)

        assertNull(queue.peekHead(neverUsedTableId))

        queue.enqueue(tableId, 100L, Chips.of(10_000), false)
        queue.enqueue(tableId, 200L, Chips.of(10_000), false)

        assertEquals(100L, queue.peekHead(tableId)?.userId)
    }

    @Test
    fun `isQueued 는 등록 전후와 제거 후 상태를 정확히 반영한다`() {
        val queue = InMemoryJoinQueue()
        val tableId = TableId(1L)
        val userId = 100L
        val neverQueuedUserId = 999L

        assertFalse(queue.isQueued(userId))

        queue.enqueue(tableId, userId, Chips.of(10_000), false)
        assertTrue(queue.isQueued(userId))
        assertFalse(queue.isQueued(neverQueuedUserId))

        queue.removeByUserId(userId)
        assertFalse(queue.isQueued(userId))
    }
}
