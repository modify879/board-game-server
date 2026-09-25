package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand
import com.jsm.boardgame.holdem.application.command.usecase.SitDownOutcome
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.OpenShowdown
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.SeatTakenException
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
import kotlin.test.assertTrue

private class SitDownFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? =
        stored.values.find { table -> table.pendingJoinRequests().any { it.userId == userId } }

    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        stored.values.filter { it.nextHandAt != null }.mapNotNull { it.id }
    override fun findAllTableIdsWithPendingJoinRequests(): List<TableId> = emptyList()

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

private class SitDownFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class SitDownFakeWalletTransfer : WalletTransfer {
    data class ToGameCall(val userId: Long, val amount: Long, val tableId: Long, val memo: String?)

    val toGameCalls = mutableListOf<ToGameCall>()

    override fun toGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        toGameCalls += ToGameCall(userId, amount, tableId, memo)
    }

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        error("SitDown 은 fromGame 을 부르지 않는다")
    }
}

private class SitDownFakeShowdownStore : ShowdownStore {
    private val store = mutableMapOf<Long, OpenShowdown>()
    override fun find(tableId: TableId): OpenShowdown? = store[tableId.value]
    override fun save(tableId: TableId, showdown: OpenShowdown) { store[tableId.value] = showdown }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

class SitDownServiceTest {

    private val tables = SitDownFakeHoldemTableRepository()
    private val handStore = SitDownFakeHandStore()
    private val showdownStore = SitDownFakeShowdownStore()
    private val walletTransfer = SitDownFakeWalletTransfer()
    private val eventPublisher = ApplicationEventPublisher { }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)
    private val revealTimeout: Duration = Duration.ofSeconds(10)
    private val handSettler = HandSettler(tables, handStore, showdownStore, eventPublisher, clock, nextHandDelay, revealTimeout)
    private val handStarter = HandStarter(tables, handStore, showdownStore, Shuffler { it }, handSettler, eventPublisher, clock, nextHandDelay)
    private val service = SitDownService(tables, handStore, walletTransfer, handStarter, clock)

    private fun createTable(): TableId = tables.save(HoldemTable.create("테스트 테이블")).id!!

    @Test
    fun `착석하면 좌석이 채워지고 지갑에서 올바른 금액·tableId 로 이체가 불린다`() {
        val tableId = createTable()

        val outcome = service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 3, buyIn = 8_000))
        assertEquals(SitDownOutcome.SEATED, outcome)

        val table = tables.findById(tableId)!!
        assertEquals(1L, table.seatAt(3)!!.userId)
        assertEquals(Chips.of(8_000), table.seatAt(3)!!.stack)

        val call = walletTransfer.toGameCalls.single()
        assertEquals(1L, call.userId)
        assertEquals(8_000L, call.amount)
        assertEquals(tableId.value, call.tableId)
    }

    @Test
    fun `존재하지 않는 테이블이면 TABLE_NOT_FOUND`() {
        val e = assertFailsWith<TableNotFoundException> {
            service.sitDown(SitDownCommand(tableId = 999, userId = 1, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `이미 다른 테이블에 앉아 있으면 핸드가 없어도 ALREADY_SEATED`() {
        val otherTable = tables.save(HoldemTable.create("다른 테이블"))
        otherTable.sitDown(1, 1, Chips.of(8_000))
        tables.save(otherTable)
        val tableId = createTable()

        val e = assertFailsWith<AlreadySeatedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
        assertEquals(0, walletTransfer.toGameCalls.size)
    }

    @Test
    fun `이미 앉은 테이블에서 핸드가 진행 중이면 HAND_IN_PROGRESS`() {
        val otherTable = tables.save(HoldemTable.create("다른 테이블"))
        otherTable.sitDown(1, 1, Chips.of(8_000))
        val savedOther = tables.save(otherTable)
        handStore.save(
            savedOther.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it }),
        )
        val tableId = createTable()

        val e = assertFailsWith<HandInProgressException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
        assertEquals(0, walletTransfer.toGameCalls.size)
    }

    @Test
    fun `이미 점유된 좌석이면 도메인 예외 SEAT_TAKEN 이 그대로 올라오고 지갑은 불리지 않는다`() {
        val tableId = createTable()
        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))

        val e = assertFailsWith<SeatTakenException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 2, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.SEAT_TAKEN, e.errorCode)
        assertEquals(1, walletTransfer.toGameCalls.size) // 첫 착석분만
    }

    @Test
    fun `바이인이 범위를 벗어나면 도메인 예외 BUY_IN_OUT_OF_RANGE 가 그대로 올라오고 지갑은 불리지 않는다`() {
        val tableId = createTable()

        val e = assertFailsWith<BuyInOutOfRangeException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 100))
        }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
        assertEquals(0, walletTransfer.toGameCalls.size)
    }

    @Test
    fun `postBlindImmediately 를 true 로 착석하면 즉시 참가 상태가 되고 진입료를 빚진다`() {
        val tableId = createTable()

        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000, postBlindImmediately = true))

        val seat = tables.findById(tableId)!!.seatAt(1)!!
        assertEquals(false, seat.awaitingBigBlind)
        assertEquals(true, seat.owesImmediatePost)
    }

    @Test
    fun `postBlindImmediately 를 생략하고 착석하면 BB 대기 상태가 되고 진입료가 없다`() {
        val tableId = createTable()

        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))

        val seat = tables.findById(tableId)!!.seatAt(1)!!
        assertEquals(true, seat.awaitingBigBlind)
        assertEquals(false, seat.owesImmediatePost)
    }

    @Test
    fun `핸드가 진행 중이면 착석 대신 참가 요청만 남기고 REQUESTED 를 반환한다`() {
        val tableId = createTable()
        val table = tables.findById(tableId)!!
        table.sitDown(1, userId = 1L, buyIn = Chips.of(8_000))
        table.sitDown(2, userId = 2L, buyIn = Chips.of(8_000))
        tables.save(table)
        val hand = Hand.start(
            mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)),
            buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2,
            HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it },
        )
        handStore.save(tableId, hand)

        val outcome = service.sitDown(SitDownCommand(tableId = tableId.value, userId = 3, seatNo = 5, buyIn = 8_000))

        assertEquals(SitDownOutcome.REQUESTED, outcome)
        assertNull(tables.findById(tableId)!!.seatAt(5))
        assertEquals(0, walletTransfer.toGameCalls.size)
        assertEquals(setOf(5), tables.findById(tableId)!!.pendingSeatNos())
    }

    @Test
    fun `이미 다른 테이블에 참가 요청을 남긴 사용자는 ALREADY_SEATED 로 거부된다`() {
        val busyTableId = createTable()
        val busyTable = tables.findById(busyTableId)!!
        busyTable.sitDown(1, userId = 10L, buyIn = Chips.of(8_000))
        busyTable.sitDown(2, userId = 11L, buyIn = Chips.of(8_000))
        tables.save(busyTable)
        val hand = Hand.start(
            mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)),
            buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2,
            HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it },
        )
        handStore.save(busyTableId, hand)
        val requested = service.sitDown(SitDownCommand(tableId = busyTableId.value, userId = 1, seatNo = 3, buyIn = 8_000))
        assertEquals(SitDownOutcome.REQUESTED, requested)

        val tableId = createTable()

        val e = assertFailsWith<AlreadySeatedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `핸드가 없으면 착석은 SEATED 를 반환하고 후보가 2명 이상이면 카운트다운을 리셋한다`() {
        val tableId = createTable()

        val first = service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))
        assertEquals(SitDownOutcome.SEATED, first)
        assertNull(tables.findById(tableId)!!.nextHandAt) // 후보 1명 — 아직 리셋 없음

        val second = service.sitDown(SitDownCommand(tableId = tableId.value, userId = 2, seatNo = 2, buyIn = 8_000))

        assertEquals(SitDownOutcome.SEATED, second)
        assertTrue(tables.findById(tableId)!!.nextHandAt != null)
    }

    @Test
    fun `카운트다운 리셋은 이미 걸린 더 늦은 nextHandAt 을 줄이지 않는다`() {
        val tableId = createTable()
        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))
        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 2, seatNo = 2, buyIn = 8_000))

        // 쇼다운 공개 선택 창 등으로 이미 5초보다 훨씬 뒤로 예약돼 있다고 가정한다.
        val farFuture = Instant.now(clock).plusSeconds(3_600)
        var table = tables.findById(tableId)!!
        table.scheduleNextHand(farFuture)
        tables.save(table)

        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 3, seatNo = 3, buyIn = 8_000))

        assertEquals(farFuture, tables.findById(tableId)!!.nextHandAt)
    }
}
