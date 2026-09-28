package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StartScheduledHandFakeTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]
    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = stored.values.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            smallBlindSeatNo = table.smallBlindSeatNo,
            bigBlindSeatNo = table.bigBlindSeatNo,
        )
        stored[id.value] = saved
        return saved
    }
}

private class StartScheduledHandFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class StartScheduledHandFakeNextHandCountdown : NextHandCountdown {
    val restarted = mutableListOf<TableId>()
    val cancelled = mutableListOf<TableId>()
    override fun restart(tableId: TableId) { restarted += tableId }
    override fun cancel(tableId: TableId) { cancelled += tableId }
    override fun remaining(tableId: TableId): Duration? = null
}

class StartScheduledHandServiceTest {

    private val tables = StartScheduledHandFakeTableRepository()
    private val handStore = StartScheduledHandFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val countdown = StartScheduledHandFakeNextHandCountdown()
    private val handSettler = HandSettler(tables, handStore, eventPublisher, countdown)
    private val handStarter = HandStarter(tables, handStore, identityShuffler, handSettler, eventPublisher, countdown)
    private val service = StartScheduledHandService(tables, handStore, handStarter)

    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in buyIns) {
            table.sitDown(userId = seatNo.toLong(), buyIn = Chips.of(buyIn))
        }
        table = tables.save(table)
        return table.id!!
    }

    @Test
    fun `테이블이 없으면 아무 일도 하지 않는다`() {
        service.start(StartScheduledHandCommand(999L))
        // 예외 없이 반환되면 충분하다.
    }

    @Test
    fun `이미 진행 중인 핸드가 있으면 아무 일도 하지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val table = tables.findById(tableId)!!
        table.moveButtonToNextOccupiedSeat()
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            buttonSeatNo = table.buttonSeatNo!!,
            smallBlindSeatNo = table.buttonSeatNo!!,
            bigBlindSeatNo = 2,
            table.smallBlind,
            table.bigBlind,
            identityShuffler,
        )
        handStore.save(tableId, hand)

        service.start(StartScheduledHandCommand(tableId.value))

        assertTrue(handStore.find(tableId) === hand)
    }

    @Test
    fun `후보가 2명 이상이면 핸드가 시작된다 — 벽시계와 무관하다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)

        service.start(StartScheduledHandCommand(tableId.value))

        assertTrue(handStore.find(tableId) != null)
    }

    @Test
    fun `후보가 2명 미만이면 핸드는 생성되지 않고 예외가 나지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L)

        service.start(StartScheduledHandCommand(tableId.value)) // 예외 없이 끝나야 한다

        assertNull(handStore.find(tableId))
    }
}
