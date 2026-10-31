package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.QueuePosition
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class PresenceFakeTableQuery(var seated: Boolean) : HoldemTableQueryRepository {
    override fun findSeatOf(userId: Long): SeatLocation? = if (seated) SeatLocation(1, 1, 100) else null
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = throw UnsupportedOperationException()
    override fun findEmptyTableIds(): List<Long> = emptyList()
}

private class PresenceFakeJoinQueue(var queued: Boolean) : JoinQueue {
    override fun enqueue(tableId: TableId, userId: UserId, postBlindImmediately: Boolean, buyIn: Chips): Int = throw UnsupportedOperationException()
    override fun peekHead(tableId: TableId): JoinQueueEntry? = null
    override fun removeByUserId(userId: UserId): TableId? = null
    override fun entriesOf(tableId: TableId): List<JoinQueueEntry> = emptyList()
    override fun isQueued(userId: UserId): Boolean = queued
    override fun tableOf(userId: UserId): TableId? = null
    override fun positionOf(userId: UserId): QueuePosition? = null
}

class UserTablePresenceQueryServiceTest {

    @Test
    fun `좌석에 앉아 있으면 true 다`() {
        assertTrue(UserTablePresenceQueryService(PresenceFakeTableQuery(true), PresenceFakeJoinQueue(false)).isAtAnyTable(1))
    }

    @Test
    fun `참가 대기열에 있으면 true 다`() {
        assertTrue(UserTablePresenceQueryService(PresenceFakeTableQuery(false), PresenceFakeJoinQueue(true)).isAtAnyTable(1))
    }

    @Test
    fun `어디에도 없으면 false 다`() {
        assertFalse(UserTablePresenceQueryService(PresenceFakeTableQuery(false), PresenceFakeJoinQueue(false)).isAtAnyTable(1))
    }
}
