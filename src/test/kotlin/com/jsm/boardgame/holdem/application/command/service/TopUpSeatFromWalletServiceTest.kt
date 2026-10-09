package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsUseCase
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TopUpFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()

    override fun findById(id: TableId): HoldemTable? = stored[id.value]
    override fun findByUserId(userId: UserId): HoldemTable? = stored.values.find { it.seatOf(userId) != null }
    override fun findAllSeatedUserIds(): List<UserId> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = stored.values.mapNotNull { it.id }
    override fun save(table: HoldemTable): HoldemTable {
        stored[table.id!!.value] = table
        return table
    }
}

/** post 는 바로 실행해 기록하고, call 은 부르면 실패한다 — 지갑 스레드가 기다리면 안 된다. */
private class TopUpFakeTableExecutor : TableExecutor {
    val postedTables = mutableListOf<TableId>()

    override fun <T> call(tableId: TableId, task: () -> T): T = error("지갑 입금 합산은 테이블 큐를 기다리면 안 된다(call 금지)")

    override fun post(tableId: TableId, task: () -> Unit) {
        postedTables += tableId
        task()
    }
}

private class TopUpFakeTopUpTableSeatsUseCase : TopUpTableSeatsUseCase {
    val commands = mutableListOf<TopUpTableSeatsCommand>()
    override fun topUp(command: TopUpTableSeatsCommand) { commands += command }
}

class TopUpSeatFromWalletServiceTest {

    private val tables = TopUpFakeHoldemTableRepository()
    private val executor = TopUpFakeTableExecutor()
    private val topUpTableSeats = TopUpFakeTopUpTableSeatsUseCase()
    private val service = TopUpSeatFromWalletService(tables, executor, topUpTableSeats)

    private fun seatedTable(): TableId {
        val table = tables.save(HoldemTable.reconstitute(TableId(1), "t", Chips.of(100), Chips.of(200), null, emptyMap()))
        table.sitDown(UserId(1000L), Chips.of(1_000))
        table.sitDown(UserId(2000L), Chips.of(1_000))
        return table.id!!
    }

    @Test
    fun `앉아 있는 사용자의 입금은 기다리지 않고 테이블 스레드에 post 로 넘긴다`() {
        val tableId = seatedTable()

        service.topUp(TopUpSeatFromWalletCommand(1000L))

        assertEquals(listOf(tableId), executor.postedTables)
        assertEquals(listOf(TopUpTableSeatsCommand(tableId.value)), topUpTableSeats.commands)
    }

    @Test
    fun `앉아 있지 않은 사용자면 아무것도 하지 않는다`() {
        seatedTable()

        service.topUp(TopUpSeatFromWalletCommand(9999L))

        assertTrue(executor.postedTables.isEmpty())
        assertTrue(topUpTableSeats.commands.isEmpty())
    }
}
