package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.RevealHandCommand
import com.jsm.boardgame.holdem.application.port.OpenShowdown
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.RevealNotAllowedException
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class RevealHandFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]?.let { copyOf(it) }
    override fun findByUserId(userId: Long): HoldemTable? = store.values.firstOrNull { it.seatOf(userId) != null }?.let { copyOf(it) }
    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllPendingNextHandTableIds(): List<TableId> = store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }
    override fun findAllTableIdsWithPendingJoinRequests(): List<TableId> = emptyList()

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: TableId(nextId++)
        val saved = copyOf(table, id)
        store[id.value] = saved
        return saved
    }

    private fun copyOf(table: HoldemTable, id: TableId = table.id!!): HoldemTable =
        HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            version = table.version,
            nextHandAt = table.nextHandAt,
        )
}

private class RevealHandFakeShowdownStore : ShowdownStore {
    private val store = mutableMapOf<Long, OpenShowdown>()
    override fun find(tableId: TableId): OpenShowdown? = store[tableId.value]
    override fun save(tableId: TableId, showdown: OpenShowdown) { store[tableId.value] = showdown }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

private class RevealHandFakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}

class RevealHandServiceTest {

    private val tables = RevealHandFakeTableRepository()
    private val showdownStore = RevealHandFakeShowdownStore()
    private val eventPublisher = RevealHandFakeEventPublisher()
    private val fixedInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)
    private val service = RevealHandService(tables, showdownStore, eventPublisher, clock, nextHandDelay)

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

    /** HandSettler.settle 이 이미 창을 연 상태를 흉내낸다 — 진 좌석(2)만 선택권이 있다. */
    private fun tableWithOpenShowdown(): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        table.sitDown(1, userId = 1000L, buyIn = Chips.of(10_000))
        table.sitDown(2, userId = 2000L, buyIn = Chips.of(10_000))
        val hand = finishedShowdownHand()
        val deadline = fixedInstant.plusSeconds(10)
        check(hand.openReveal(deadline))
        table.scheduleNextHand(deadline.plus(nextHandDelay))
        table = tables.save(table)
        showdownStore.save(table.id!!, OpenShowdown(hand, mapOf(2000L to 2)))
        return table.id!!
    }

    @Test
    fun `공개 선택 창이 없으면 REVEAL_NOT_ALLOWED 를 던진다`() {
        val e = assertFailsWith<RevealNotAllowedException> {
            service.reveal(RevealHandCommand(tableId = 999, userId = 2000L, action = "SHOW"))
        }
        assertEquals(HoldemErrorCode.REVEAL_NOT_ALLOWED, e.errorCode)
    }

    @Test
    fun `선택 대상이 아닌 사용자가 고르면 REVEAL_NOT_ALLOWED 를 던진다`() {
        val tableId = tableWithOpenShowdown()

        // 1000(좌석1, 승자)은 자동 공개 대상이라 선택권이 없다.
        val e = assertFailsWith<RevealNotAllowedException> {
            service.reveal(RevealHandCommand(tableId = tableId.value, userId = 1000L, action = "SHOW"))
        }

        assertEquals(HoldemErrorCode.REVEAL_NOT_ALLOWED, e.errorCode)
    }

    @Test
    fun `전원이 선택을 마치면 공개 선택 창이 닫히고 다음 핸드 시작 시각이 당겨진다`() {
        val tableId = tableWithOpenShowdown()

        service.reveal(RevealHandCommand(tableId = tableId.value, userId = 2000L, action = "SHOW"))

        assertNull(showdownStore.find(tableId))
        assertEquals(fixedInstant.plus(nextHandDelay), tables.findById(tableId)!!.nextHandAt)
    }
}
