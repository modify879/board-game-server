package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import kotlin.test.Test
import kotlin.test.assertEquals

private class UpdateSeatPresenceFakeHoldemTableRepository : HoldemTableRepository {
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

class UpdateSeatPresenceServiceTest {

    private val tables = UpdateSeatPresenceFakeHoldemTableRepository()
    private val service = UpdateSeatPresenceService(tables)

    @Test
    fun `착석한 사용자의 좌석 연결 상태를 바꾼다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(1, 1, Chips.of(8_000))
        val saved = tables.save(table)

        service.update(UpdateSeatPresenceCommand(userId = 1, presence = "DISCONNECTED"))

        assertEquals(SeatPresence.DISCONNECTED, tables.findById(saved.id!!)!!.seatAt(1)!!.presence)
    }

    @Test
    fun `미착석 사용자의 이벤트는 조용히 끝낸다`() {
        service.update(UpdateSeatPresenceCommand(userId = 999, presence = "DISCONNECTED"))
        // 예외 없이 끝나면 통과.
    }
}
