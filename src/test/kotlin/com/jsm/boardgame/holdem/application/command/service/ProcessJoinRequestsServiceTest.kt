package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.DropJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.DropJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsCommand
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ProcessJoinRequestsFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    override fun findById(id: TableId): HoldemTable? = store[id.value]
    override fun findByUserId(userId: Long): HoldemTable? = null
    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<Long> = emptyList()
    override fun findAllPendingNextHandTableIds(): List<TableId> = emptyList()
    override fun findAllTableIdsWithPendingJoinRequests(): List<TableId> =
        store.values.filter { it.pendingJoinRequests().isNotEmpty() }.mapNotNull { it.id }
    override fun save(table: HoldemTable): HoldemTable { store[table.id!!.value] = table; return table }
    fun put(table: HoldemTable) { store[table.id!!.value] = table }
}

private class ProcessJoinRequestsFakeAdmitUseCase(private val failingUserIds: Set<Long> = emptySet()) : AdmitJoinRequestUseCase {
    val calls = mutableListOf<AdmitJoinRequestCommand>()
    override fun admit(command: AdmitJoinRequestCommand) {
        calls += command
        if (command.userId in failingUserIds) {
            throw AlreadySeatedException("test 용 실패: userId=${command.userId}")
        }
    }
}

private class ProcessJoinRequestsFakeDropUseCase : DropJoinRequestUseCase {
    val calls = mutableListOf<DropJoinRequestCommand>()
    override fun drop(command: DropJoinRequestCommand) { calls += command }
}

class ProcessJoinRequestsServiceTest {

    private val baseInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun tableWithPendingRequests(vararg userIds: Long): HoldemTable {
        val table = HoldemTable.reconstitute(
            id = TableId(1),
            name = "test-table",
            smallBlind = Chips.of(100),
            bigBlind = Chips.of(200),
            buttonSeatNo = null,
            seats = emptyMap(),
            version = 0,
        )
        userIds.forEachIndexed { idx, userId ->
            table.requestJoin(
                userId = userId,
                seatNo = idx + 1,
                buyIn = Chips.of(8_000),
                postBlindImmediately = false,
                requestedAt = baseInstant.plusSeconds(idx.toLong()),
            )
        }
        return table
    }

    @Test
    fun `한 요청이 실패해도 나머지 요청은 계속 처리된다`() {
        val tables = ProcessJoinRequestsFakeTableRepository()
        tables.put(tableWithPendingRequests(101L, 102L, 103L))
        val admit = ProcessJoinRequestsFakeAdmitUseCase(failingUserIds = setOf(102L))
        val drop = ProcessJoinRequestsFakeDropUseCase()
        val service = ProcessJoinRequestsService(tables, admit, drop)

        service.process(ProcessJoinRequestsCommand(1L))

        assertEquals(listOf(101L, 102L, 103L), admit.calls.map { it.userId })
    }

    @Test
    fun `실패한 요청은 버려진다`() {
        val tables = ProcessJoinRequestsFakeTableRepository()
        tables.put(tableWithPendingRequests(101L, 102L, 103L))
        val admit = ProcessJoinRequestsFakeAdmitUseCase(failingUserIds = setOf(102L))
        val drop = ProcessJoinRequestsFakeDropUseCase()
        val service = ProcessJoinRequestsService(tables, admit, drop)

        service.process(ProcessJoinRequestsCommand(1L))

        assertEquals(listOf(102L), drop.calls.map { it.userId })
    }

    @Test
    fun `테이블이 없으면 아무 일도 하지 않는다`() {
        val tables = ProcessJoinRequestsFakeTableRepository()
        val admit = ProcessJoinRequestsFakeAdmitUseCase()
        val drop = ProcessJoinRequestsFakeDropUseCase()
        val service = ProcessJoinRequestsService(tables, admit, drop)

        service.process(ProcessJoinRequestsCommand(999L))

        assertTrue(admit.calls.isEmpty())
    }

    @Test
    fun `경합으로 실패하면 버리지 않는다`() {
        val tables = ProcessJoinRequestsFakeTableRepository()
        tables.put(tableWithPendingRequests(101L))
        val admit = object : AdmitJoinRequestUseCase {
            override fun admit(command: AdmitJoinRequestCommand) {
                throw ConcurrentTableUpdateException("경합")
            }
        }
        val drop = ProcessJoinRequestsFakeDropUseCase()
        val service = ProcessJoinRequestsService(tables, admit, drop)

        service.process(ProcessJoinRequestsCommand(1L))

        assertTrue(drop.calls.isEmpty())
    }
}
