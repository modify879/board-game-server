package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class ExpireConnectionFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

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
        )
        stored[id.value] = saved
        return saved
    }
}

private class ExpireConnectionFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()
    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
}

private class ExpireConnectionFakeStandUpUseCase : StandUpUseCase {
    val calls = mutableListOf<StandUpCommand>()
    override fun standUp(command: StandUpCommand) {
        calls += command
    }
}

class ExpireConnectionServiceTest {

    private val tables = ExpireConnectionFakeHoldemTableRepository()
    private val handStore = ExpireConnectionFakeHandStore()
    private val standUpUseCase = ExpireConnectionFakeStandUpUseCase()
    private val service = ExpireConnectionService(tables, handStore, standUpUseCase)

    @Test
    fun `미착석 사용자는 조용히 끝나고 null 을 돌려준다`() {
        val result = service.expire(ExpireConnectionCommand(userId = 999))

        assertNull(result)
        assertEquals(0, standUpUseCase.calls.size)
    }

    @Test
    fun `핸드가 없으면 즉시 기립시키고 null 을 돌려준다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(1, 1, Chips.of(8_000))
        tables.save(table)

        val result = service.expire(ExpireConnectionCommand(userId = 1))

        assertNull(result)
        assertEquals(1, standUpUseCase.calls.size)
        assertEquals(1L, standUpUseCase.calls[0].userId)
    }

    @Test
    fun `핸드가 진행 중이면 즉시 기립시키지 않고 테이블 id 를 돌려준다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(1, 1, Chips.of(8_000))
        val saved = tables.save(table)
        handStore.save(
            saved.id!!,
            Hand.start(
                mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)),
                buttonSeatNo = 1,
                HoldemTable.SMALL_BLIND,
                HoldemTable.BIG_BLIND,
                Shuffler { it },
            ),
        )

        val result = service.expire(ExpireConnectionCommand(userId = 1))

        assertEquals(saved.id, result)
        assertEquals(0, standUpUseCase.calls.size)
    }
}
