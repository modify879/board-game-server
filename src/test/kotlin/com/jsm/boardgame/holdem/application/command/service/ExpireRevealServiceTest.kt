package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireRevealCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.OpenShowdown
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ExpireRevealFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]
    override fun findByUserId(userId: Long): HoldemTable? = store.values.firstOrNull { it.seatOf(userId) != null }
    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllPendingNextHandTableIds(): List<TableId> = store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }
    override fun findAllTableIdsWithPendingJoinRequests(): List<TableId> = emptyList()

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: TableId(nextId++)
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
        store[id.value] = saved
        return saved
    }
}

private class ExpireRevealFakeShowdownStore : ShowdownStore {
    private val store = mutableMapOf<Long, OpenShowdown>()
    override fun find(tableId: TableId): OpenShowdown? = store[tableId.value]
    override fun save(tableId: TableId, showdown: OpenShowdown) { store[tableId.value] = showdown }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

private class ExpireRevealFakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}

class ExpireRevealServiceTest {

    private val tables = ExpireRevealFakeTableRepository()
    private val showdownStore = ExpireRevealFakeShowdownStore()
    private val eventPublisher = ExpireRevealFakeEventPublisher()
    private val service = ExpireRevealService(tables, showdownStore, eventPublisher)

    /** 좌석1=As,Ah(AA) 좌석2=7c,2d(무패), 보드=Kd,Qc,Jh,9s,4h — 좌석1이 반드시 이긴다. */
    private fun finishedShowdownHand(): Hand {
        val ordered = listOf("7c", "As", "2d", "Ah", "Kd", "Qc", "Jh", "9s", "4h").map { Card.of(it) }
        val shuffler = Shuffler { all -> ordered + all.filterNot { it in ordered } }
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            buttonSeatNo = 1,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            Chips.of(100),
            Chips.of(200),
            shuffler,
        )
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Check)
        repeat(3) {
            hand.act(2, BettingAction.Check)
            hand.act(1, BettingAction.Check)
        }
        return hand
    }

    private fun tableWithOpenShowdown(): Pair<TableId, Hand> {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        table.sitDown(1, userId = 1000L, buyIn = Chips.of(10_000))
        table.sitDown(2, userId = 2000L, buyIn = Chips.of(10_000))
        val hand = finishedShowdownHand()
        check(hand.openReveal(Instant.parse("2026-01-01T00:00:10Z")))
        table.scheduleNextHand(Instant.parse("2026-01-01T00:00:15Z"))
        table = tables.save(table)
        showdownStore.save(table.id!!, OpenShowdown(hand, mapOf(2000L to 2)))
        return table.id!! to hand
    }

    @Test
    fun `제한시간이 만료되면 아직 선택하지 않은 좌석은 전부 MUCK 되고 창이 닫힌다`() {
        val (tableId, hand) = tableWithOpenShowdown()

        service.expire(ExpireRevealCommand(tableId.value))

        assertNull(showdownStore.find(tableId))
        assertEquals(emptySet(), hand.awaitingRevealSeatNos)
        assertEquals(setOf(1), hand.shownSeatNos) // 자동 공개된 승자만 남고 2는 머크됐다
        assertTrue(eventPublisher.events.any { it is HandBroadcastRequested && it.tableId == tableId })
    }

    @Test
    fun `이미 만료되지 않은 nextHandAt 은 건드리지 않는다`() {
        val (tableId, _) = tableWithOpenShowdown()
        val beforeExpire = tables.findById(tableId)!!.nextHandAt

        service.expire(ExpireRevealCommand(tableId.value))

        assertEquals(beforeExpire, tables.findById(tableId)!!.nextHandAt)
    }

    @Test
    fun `공개 선택 창이 없으면 조용히 끝난다`() {
        service.expire(ExpireRevealCommand(999))

        assertEquals(0, eventPublisher.events.size)
    }
}
