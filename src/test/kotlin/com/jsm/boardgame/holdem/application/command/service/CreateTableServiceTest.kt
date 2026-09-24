package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CreateTableCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private class CreateTableFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

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
        )
        stored[id.value] = saved
        return saved
    }
}

private class CreateTableFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

class CreateTableServiceTest {

    private val tables = CreateTableFakeHoldemTableRepository()
    private val handStore = CreateTableFakeHandStore()
    private val service = CreateTableService(tables, handStore)

    private fun seatedTable(userId: Long): HoldemTable {
        val table = HoldemTable.create("기존 테이블")
        table.sitDown(1, userId, Chips.of(8_000))
        return tables.save(table)
    }

    @Test
    fun `이름을 주면 테이블이 생성되고 id 가 발급된다`() {
        val id = service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블"))

        assertNotNull(tables.findById(id))
        assertEquals("새 테이블", tables.findById(id)!!.name)
    }

    @Test
    fun `이미 다른 테이블에 앉아 있으면 핸드가 없어도 ALREADY_SEATED`() {
        seatedTable(userId = 1)

        val e = assertFailsWith<AlreadySeatedException> {
            service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블"))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `이미 앉은 테이블에서 핸드가 진행 중이면 HAND_IN_PROGRESS`() {
        val table = seatedTable(userId = 1)
        handStore.save(
            table.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it }),
        )

        val e = assertFailsWith<HandInProgressException> {
            service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블"))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
    }
}
