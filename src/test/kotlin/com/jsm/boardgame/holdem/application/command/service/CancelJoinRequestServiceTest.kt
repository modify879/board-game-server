package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.JoinRequestNotFoundException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class CancelJoinRequestFakeTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? =
        stored.values.find { table -> table.pendingJoinRequests().any { it.userId == userId } }

    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        stored.values.filter { it.nextHandAt != null }.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            version = table.version,
            nextHandAt = table.nextHandAt,
            joinRequests = table.pendingJoinRequests().associateBy { it.seatNo },
        )
        stored[id.value] = saved
        return saved
    }
}

class CancelJoinRequestServiceTest {

    private val tables = CancelJoinRequestFakeTableRepository()
    private val service = CancelJoinRequestService(tables)

    private fun createTable(): TableId = tables.save(HoldemTable.create("테스트 테이블")).id!!

    @Test
    fun `취소하면 대기 중인 참가 요청이 제거된다`() {
        val tableId = createTable()
        val table = tables.findById(tableId)!!
        table.requestJoin(userId = 1L, seatNo = 3, buyIn = Chips.of(10_000), postBlindImmediately = false, requestedAt = Instant.now())
        tables.save(table)

        service.cancel(CancelJoinRequestCommand(tableId.value, userId = 1L))

        assertTrue(tables.findById(tableId)!!.pendingJoinRequests().isEmpty())
    }

    @Test
    fun `대기 중인 요청이 없으면 JOIN_REQUEST_NOT_FOUND 다`() {
        val tableId = createTable()

        val e = assertFailsWith<JoinRequestNotFoundException> {
            service.cancel(CancelJoinRequestCommand(tableId.value, userId = 1L))
        }
        assertEquals(HoldemErrorCode.JOIN_REQUEST_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `다른 사용자의 요청은 취소되지 않고 그대로 남는다`() {
        val tableId = createTable()
        val table = tables.findById(tableId)!!
        table.requestJoin(userId = 1L, seatNo = 3, buyIn = Chips.of(10_000), postBlindImmediately = false, requestedAt = Instant.now())
        tables.save(table)

        assertFailsWith<JoinRequestNotFoundException> {
            service.cancel(CancelJoinRequestCommand(tableId.value, userId = 2L))
        }

        assertEquals(1, tables.findById(tableId)!!.pendingJoinRequests().size)
    }

    @Test
    fun `존재하지 않는 테이블이면 TABLE_NOT_FOUND 다`() {
        val e = assertFailsWith<TableNotFoundException> {
            service.cancel(CancelJoinRequestCommand(999L, userId = 1L))
        }
        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }
}
