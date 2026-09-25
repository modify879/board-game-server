package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.OpenShowdown
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class HandStarterFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]?.let { copyOf(it) }

    override fun findByUserId(userId: Long): HoldemTable? =
        store.values.firstOrNull { it.seatOf(userId) != null }?.let { copyOf(it) }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null

    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }
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
            smallBlindSeatNo = table.smallBlindSeatNo,
            bigBlindSeatNo = table.bigBlindSeatNo,
            nextHandAt = table.nextHandAt,
        )
}

private class HandStarterFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

private class HandStarterFakeShowdownStore : ShowdownStore {
    private val store = mutableMapOf<Long, OpenShowdown>()
    override fun find(tableId: TableId): OpenShowdown? = store[tableId.value]
    override fun save(tableId: TableId, showdown: OpenShowdown) { store[tableId.value] = showdown }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

class HandStarterTest {

    private val tables = HandStarterFakeTableRepository()
    private val handStore = HandStarterFakeHandStore()
    private val showdownStore = HandStarterFakeShowdownStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)
    private val revealTimeout: Duration = Duration.ofSeconds(10)
    private val handSettler = HandSettler(tables, handStore, showdownStore, eventPublisher, clock, nextHandDelay, revealTimeout)
    private val handStarter = HandStarter(tables, handStore, showdownStore, identityShuffler, handSettler, eventPublisher, clock, nextHandDelay)

    private fun start(tableId: TableId) {
        val table = tables.findById(tableId)!!
        handStarter.start(tableId, table)
    }

    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in buyIns) {
            table.sitDown(seatNo, userId = seatNo.toLong(), buyIn = Chips.of(buyIn))
        }
        table = tables.save(table)
        return table.id!!
    }

    /** BB 즉시 포스팅을 특정 좌석에만 골라 앉힐 때 쓴다. */
    private fun sitDownChoosing(tableId: TableId, seatNo: Int, buyIn: Long, postBlindImmediately: Boolean) {
        val table = tables.findById(tableId)!!
        table.sitDown(seatNo, userId = seatNo.toLong(), buyIn = Chips.of(buyIn), postBlindImmediately = postBlindImmediately)
        tables.save(table)
    }

    @Test
    fun `핸드를 시작하면 첫 핸드는 가장 작은 참가 좌석이 BB 가 되고 핸드가 저장된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)

        start(tableId)

        val savedTable = tables.findById(tableId)!!
        assertEquals(2, savedTable.buttonSeatNo)
        assertEquals(3, savedTable.smallBlindSeatNo)
        assertEquals(1, savedTable.bigBlindSeatNo)
        val hand = handStore.find(tableId)
        assertNotNull(hand)
        assertEquals(false, hand.isFinished)
    }

    @Test
    fun `핸드가 시작되면 남아있던 공개 선택 창을 닫는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val staleHand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2,
            Chips.of(100), Chips.of(200), identityShuffler,
        )
        showdownStore.save(tableId, OpenShowdown(staleHand, emptyMap()))

        start(tableId)

        assertNull(showdownStore.find(tableId))
    }

    @Test
    fun `참가 후보가 2명 미만이면 시작하지 않고 false 를 반환한다`() {
        val tableId = tableWithSeats(1 to 10_000L)
        val table = tables.findById(tableId)!!

        val started = handStarter.start(tableId, table)

        assertFalse(started)
        assertNull(handStore.find(tableId))
        assertEquals(1, tables.findById(tableId)!!.occupiedSeats().size)
    }

    @Test
    fun `0칩 좌석은 핸드에서 제외된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val table = tables.findById(tableId)!!
        table.applyStacks(mapOf(2 to Chips.ZERO))
        tables.save(table)

        start(tableId)

        val hand = handStore.find(tableId)!!
        assertEquals(Chips.of(9_900), hand.stackOf(1))
        assertFailsWith<NoSuchElementException> { hand.stackOf(2) }
        assertEquals(Chips.of(9_800), hand.stackOf(3))
    }

    @Test
    fun `직전 SB 좌석이 빈 채로 다음 핸드를 시작하면 버튼이 그 좌석 번호에 그대로 남는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L, 4 to 10_000L)

        start(tableId) // 1핸드(첫 핸드): BB=1, SB=4, 버튼=3
        handStore.remove(tableId) // 정산 없이 다음 핸드로 넘어가는 상황을 흉내낸다

        var table = tables.findById(tableId)!!
        table.applyStacks(mapOf(4 to Chips.ZERO)) // 직전 SB(4) 좌석이 이번 핸드엔 없다
        tables.save(table)

        start(tableId) // 2핸드: 직전 SB(4)가 다음 버튼이 되는데, 비어 있어도 그대로(dead button)

        val savedTable = tables.findById(tableId)!!
        assertEquals(4, savedTable.buttonSeatNo)
        val hand = handStore.find(tableId)!!
        assertEquals(4, hand.buttonSeatNo)
        assertFailsWith<NoSuchElementException> { hand.stackOf(4) } // 4번은 이번 핸드에 없다
    }

    @Test
    fun `핸드가 시작 직후 끝나면 즉시 정산하고 핸드를 저장하지 않는다`() {
        val tableId = tableWithSeats(1 to 8_000L, 2 to 8_000L)
        val table = tables.findById(tableId)!!
        table.applyStacks(mapOf(1 to Chips.of(100), 2 to Chips.of(100)))
        tables.save(table)

        start(tableId)

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        val total = savedTable.seatAt(1)!!.stack + savedTable.seatAt(2)!!.stack
        assertEquals(Chips.of(200), total)
    }

    @Test
    fun `테이블의 첫 핸드는 새로 앉은 좌석들이 모두 대기(기본값)를 골라도 정상적으로 시작된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)

        start(tableId)

        val hand = handStore.find(tableId)!!
        assertEquals(Chips.of(10_000) - HoldemTable.BIG_BLIND, hand.stackOf(1))
        assertEquals(Chips.of(10_000), hand.stackOf(2))
        assertEquals(Chips.of(10_000) - HoldemTable.SMALL_BLIND, hand.stackOf(3))

        val savedTable = tables.findById(tableId)!!
        assertFalse(savedTable.seatAt(1)!!.awaitingBigBlind)
        assertFalse(savedTable.seatAt(2)!!.awaitingBigBlind)
        assertFalse(savedTable.seatAt(3)!!.awaitingBigBlind)
    }

    @Test
    fun `대기를 선택하면 BB 가 자기 자리에 올 때까지 새 좌석은 핸드에 참가하지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        start(tableId) // 1핸드: BB=1
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 4, buyIn = 10_000L, postBlindImmediately = false)

        start(tableId) // 2핸드: BB=2 (4 아님)

        val savedTable = tables.findById(tableId)!!
        assertEquals(2, savedTable.bigBlindSeatNo)
        val hand = handStore.find(tableId)!!
        assertFailsWith<NoSuchElementException> { hand.stackOf(4) }
        assertTrue(tables.findById(tableId)!!.seatAt(4)!!.awaitingBigBlind)
    }

    @Test
    fun `대기 중인 좌석에 BB 가 도달하면 참가하고 대기 플래그가 풀린다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        start(tableId) // 1핸드: BB=1
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 4, buyIn = 10_000L, postBlindImmediately = false)

        start(tableId) // 2핸드: BB=2
        handStore.remove(tableId)
        start(tableId) // 3핸드: BB=3
        handStore.remove(tableId)
        start(tableId) // 4핸드: BB=4 -> 대기 풀림

        val savedTable = tables.findById(tableId)!!
        assertEquals(4, savedTable.bigBlindSeatNo)
        val hand = handStore.find(tableId)!!
        assertEquals(Chips.of(10_000) - HoldemTable.BIG_BLIND, hand.stackOf(4))
        assertFalse(tables.findById(tableId)!!.seatAt(4)!!.awaitingBigBlind)
    }

    @Test
    fun `즉시 포스팅을 선택하면 다음 핸드에 바로 참가하고 BB 만큼 추가로 포스팅한다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        start(tableId) // 1핸드: BB=1
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 4, buyIn = 10_000L, postBlindImmediately = true)

        start(tableId) // 2핸드: BB=2, 4는 즉시 참가

        val savedTable = tables.findById(tableId)!!
        assertEquals(2, savedTable.bigBlindSeatNo)
        val hand = handStore.find(tableId)!!
        assertEquals(Chips.of(10_000) - HoldemTable.BIG_BLIND, hand.stackOf(4))
        assertFalse(tables.findById(tableId)!!.seatAt(4)!!.owesImmediatePost)
    }

    @Test
    fun `즉시 포스팅은 한 번만 부과된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        start(tableId) // 1핸드: BB=1
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 4, buyIn = 10_000L, postBlindImmediately = true)

        start(tableId) // 2핸드: BB=2, 4는 진입료 납부
        handStore.remove(tableId)

        start(tableId) // 3핸드: BB=3, 4는 이번엔 SB/BB 아님

        val hand = handStore.find(tableId)!!
        assertEquals(3, tables.findById(tableId)!!.bigBlindSeatNo)
        assertEquals(Chips.of(10_000), hand.stackOf(4))
    }

    @Test
    fun `핵심 공정성 — 대기를 선택해도 한 바퀴 동안 낸 블라인드 총액이 기존 참가자보다 적지 않다`() {
        val buyIn = 10_000L
        val tableId = tableWithSeats(1 to buyIn, 2 to buyIn, 3 to buyIn)
        start(tableId) // 회전을 고정하는 1핸드(공정성 집계에선 뺀다)
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 4, buyIn = buyIn, postBlindImmediately = false)

        var seat1Total = Chips.ZERO
        var seat4Total = Chips.ZERO
        repeat(3) {
            start(tableId)
            val hand = handStore.find(tableId)!!
            seat1Total += runCatching { Chips.of(buyIn) - hand.stackOf(1) }.getOrDefault(Chips.ZERO)
            seat4Total += runCatching { Chips.of(buyIn) - hand.stackOf(4) }.getOrDefault(Chips.ZERO)
            handStore.remove(tableId)
        }

        assertTrue(seat4Total >= seat1Total)
    }

    @Test
    fun `핵심 공정성 — 즉시 포스팅을 선택하면 한 바퀴 동안 낸 블라인드 총액이 기존 참가자보다 적지 않다`() {
        val buyIn = 10_000L
        val tableId = tableWithSeats(1 to buyIn, 2 to buyIn, 3 to buyIn)
        start(tableId)
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 4, buyIn = buyIn, postBlindImmediately = true)

        var seat1Total = Chips.ZERO
        var seat4Total = Chips.ZERO
        repeat(3) {
            start(tableId)
            val hand = handStore.find(tableId)!!
            seat1Total += runCatching { Chips.of(buyIn) - hand.stackOf(1) }.getOrDefault(Chips.ZERO)
            seat4Total += runCatching { Chips.of(buyIn) - hand.stackOf(4) }.getOrDefault(Chips.ZERO)
            handStore.remove(tableId)
        }

        assertTrue(seat4Total >= seat1Total)
    }

    @Test
    fun `비었다가 다시 찬 테이블은 대기 좌석만 있어도 예외 없이 핸드가 시작되고 둘 다 참가한다`() {
        val tableId = tableWithSeats(5 to 10_000L, 6 to 10_000L, 7 to 10_000L)
        start(tableId) // bigBlindSeatNo 를 채워둔다(첫 핸드는 전원 참가 완화가 적용된다)
        handStore.remove(tableId)

        var table = tables.findById(tableId)!!
        table.standUp(5)
        table.standUp(6)
        table.standUp(7)
        tables.save(table)

        sitDownChoosing(tableId, seatNo = 1, buyIn = 10_000L, postBlindImmediately = false)
        sitDownChoosing(tableId, seatNo = 2, buyIn = 10_000L, postBlindImmediately = false)

        start(tableId)

        val hand = handStore.find(tableId)!!
        assertEquals(Chips.of(10_000) - HoldemTable.SMALL_BLIND, hand.stackOf(2))
        assertEquals(Chips.of(10_000) - HoldemTable.BIG_BLIND, hand.stackOf(1))

        val savedTable = tables.findById(tableId)!!
        assertFalse(savedTable.seatAt(1)!!.awaitingBigBlind)
        assertFalse(savedTable.seatAt(2)!!.awaitingBigBlind)
    }

    @Test
    fun `정규 참가자가 파산해 대기자만 남으면 예외 없이 핸드가 시작되고 대기 플래그가 풀린다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        start(tableId) // 1핸드: 1·2 정상 참가, 대기 없음
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 3, buyIn = 10_000L, postBlindImmediately = false) // 3은 대기

        var table = tables.findById(tableId)!!
        table.applyStacks(mapOf(2 to Chips.ZERO)) // 정규 참가자 2가 파산해 후보에서 빠진다
        tables.save(table)

        start(tableId) // 후보={1,3}, 대기 제외 참가자={1} < 2 → 완화

        val hand = handStore.find(tableId)!!
        assertFailsWith<NoSuchElementException> { hand.stackOf(2) }
        assertNotNull(runCatching { hand.stackOf(1) }.getOrNull())
        assertNotNull(runCatching { hand.stackOf(3) }.getOrNull())

        val savedTable = tables.findById(tableId)!!
        assertFalse(savedTable.seatAt(3)!!.awaitingBigBlind)
    }

    @Test
    fun `참가자가 이미 2명 이상이면 대기 좌석은 완화 없이 그대로 대기한다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        start(tableId) // 1핸드: BB=1
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 4, buyIn = 10_000L, postBlindImmediately = false)

        start(tableId) // 2핸드: 참가자={1,2,3}(3명) — 완화가 필요 없다

        val hand = handStore.find(tableId)!!
        assertFailsWith<NoSuchElementException> { hand.stackOf(4) }
        assertTrue(tables.findById(tableId)!!.seatAt(4)!!.awaitingBigBlind)
    }

    @Test
    fun `스택 총합은 보존된다`() {
        val buyIn = 10_000L
        val tableId = tableWithSeats(1 to buyIn, 2 to buyIn, 3 to buyIn)
        start(tableId)
        handStore.remove(tableId)
        sitDownChoosing(tableId, seatNo = 4, buyIn = buyIn, postBlindImmediately = true)

        start(tableId)

        val hand = handStore.find(tableId)!!
        val participants = listOf(1, 2, 3, 4)
        val stacksTotal = participants.fold(Chips.ZERO) { acc, seatNo -> acc + hand.stackOf(seatNo) }
        assertEquals(Chips.of(buyIn * participants.size), stacksTotal + hand.potTotal())
    }

    @Test
    fun `헤즈업 뒤 대기 중인 세 번째 좌석 때문에 버튼이 BB 와 겹치지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 3 to 10_000L)
        start(tableId) // 1핸드: 헤즈업 1·3
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 2, buyIn = 10_000L, postBlindImmediately = false)

        start(tableId) // 2핸드: 참가자={1,3}(2는 대기)

        var hand = handStore.find(tableId)!!
        assertEquals(3, hand.buttonSeatNo)
        assertEquals(Chips.of(10_000) - HoldemTable.SMALL_BLIND, hand.stackOf(3))
        assertEquals(Chips.of(10_000) - HoldemTable.BIG_BLIND, hand.stackOf(1))
        assertFailsWith<NoSuchElementException> { hand.stackOf(2) }
        handStore.remove(tableId)

        start(tableId) // 3핸드: BB 가 2에 도달 -> 딜인, 3인 참가

        val savedTable = tables.findById(tableId)!!
        assertEquals(3, savedTable.buttonSeatNo)
        assertEquals(1, savedTable.smallBlindSeatNo)
        assertEquals(2, savedTable.bigBlindSeatNo)
        hand = handStore.find(tableId)!!
        assertNotNull(runCatching { hand.stackOf(2) }.getOrNull())
    }

    @Test
    fun `헤즈업 뒤 즉시 포스팅을 고른 좌석이 버튼 자리에 걸리면 이번 핸드는 대기한다`() {
        val tableId = tableWithSeats(1 to 10_000L, 3 to 10_000L)
        start(tableId) // 1핸드: 헤즈업 1·3
        handStore.remove(tableId)

        sitDownChoosing(tableId, seatNo = 2, buyIn = 10_000L, postBlindImmediately = true)

        start(tableId) // 2핸드: 2는 명목 버튼 자리 -> 딜인되지 않는다

        var hand = handStore.find(tableId)!!
        assertFailsWith<NoSuchElementException> { hand.stackOf(2) }
        assertTrue(tables.findById(tableId)!!.seatAt(2)!!.owesImmediatePost)
        handStore.remove(tableId)

        start(tableId) // 3핸드: 2는 BB -> 딜인, 추가 포스팅 없이 BB 만 낸다

        hand = handStore.find(tableId)!!
        assertEquals(2, tables.findById(tableId)!!.bigBlindSeatNo)
        assertEquals(Chips.of(10_000) - HoldemTable.BIG_BLIND, hand.stackOf(2))
        assertFalse(tables.findById(tableId)!!.seatAt(2)!!.owesImmediatePost)
    }

    @Test
    fun `SB 자리에 걸린 즉시 포스팅 좌석은 추가 포스팅 없이 SB 만 낸다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        sitDownChoosing(tableId, seatNo = 3, buyIn = 10_000L, postBlindImmediately = true)

        start(tableId) // 1핸드: 버튼=2, SB=3, BB=1 (완화로 전원 참가)

        val hand = handStore.find(tableId)!!
        assertEquals(Chips.of(10_000) - HoldemTable.SMALL_BLIND, hand.stackOf(3))
        assertFalse(tables.findById(tableId)!!.seatAt(3)!!.owesImmediatePost)

        hand.act(2, BettingAction.Call)
        hand.act(3, BettingAction.Call)

        assertEquals(Chips.of(200), hand.totalContributedBy(3))
    }

    @Test
    fun `기립 후 즉시 포스팅으로 재입장한 좌석은 명목 SB·버튼을 지나야 딜인된다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L, 4 to 10_000L)
        start(tableId) // 1핸드: BB=1
        handStore.remove(tableId)
        start(tableId) // 2핸드: BB=2
        handStore.remove(tableId)

        var table = tables.findById(tableId)!!
        table.standUp(2L)
        tables.save(table)
        sitDownChoosing(tableId, seatNo = 2, buyIn = 10_000L, postBlindImmediately = true)

        start(tableId) // 3핸드: 2는 명목 SB -> dead SB, 딜인되지 않는다
        var hand = handStore.find(tableId)!!
        assertFailsWith<NoSuchElementException> { hand.stackOf(2) }
        assertTrue(tables.findById(tableId)!!.seatAt(2)!!.owesImmediatePost)
        handStore.remove(tableId)

        start(tableId) // 4핸드: 2는 명목 버튼 -> 여전히 딜인되지 않는다
        hand = handStore.find(tableId)!!
        assertFailsWith<NoSuchElementException> { hand.stackOf(2) }
        assertTrue(tables.findById(tableId)!!.seatAt(2)!!.owesImmediatePost)
        handStore.remove(tableId)

        start(tableId) // 5핸드: 딜인, 추가 BB 포스팅
        hand = handStore.find(tableId)!!
        assertEquals(Chips.of(10_000) - HoldemTable.BIG_BLIND, hand.stackOf(2))
        assertFalse(tables.findById(tableId)!!.seatAt(2)!!.owesImmediatePost)
    }
}
