package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.OpenShowdown
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
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

private class AdmitJoinRequestFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? {
        val saved = store[id.value] ?: return null
        return HoldemTable.reconstitute(
            id = saved.id!!,
            name = saved.name,
            smallBlind = saved.smallBlind,
            bigBlind = saved.bigBlind,
            buttonSeatNo = saved.buttonSeatNo,
            seats = saved.occupiedSeats().associateBy { it.seatNo },
            version = saved.version,
            nextHandAt = saved.nextHandAt,
            joinRequests = saved.pendingJoinRequests().associateBy { it.seatNo },
        )
    }
    override fun findByUserId(userId: Long): HoldemTable? = store.values.find { it.seatOf(userId) != null }
    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllPendingNextHandTableIds(): List<TableId> = store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }
    override fun findAllTableIdsWithPendingJoinRequests(): List<TableId> =
        store.values.filter { it.pendingJoinRequests().isNotEmpty() }.mapNotNull { it.id }

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
            joinRequests = table.pendingJoinRequests().associateBy { it.seatNo },
        )
        store[id.value] = saved
        return saved
    }
}

private class AdmitJoinRequestFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

/** 실패시킬 사용자 id 를 지정하면 그 사용자의 toGame 호출에서 BusinessException 을 던져
 *  잔액 부족 같은 지갑 실패를 흉내낸다(holdem 은 wallet 의 domain 을 참조하지 않는다). */
private class AdmitJoinRequestFakeWalletTransfer(private val failingUserIds: Set<Long> = emptySet()) : WalletTransfer {
    data class ToGameCall(val userId: Long, val amount: Long, val tableId: Long, val memo: String?)

    val toGameCalls = mutableListOf<ToGameCall>()

    override fun toGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        if (userId in failingUserIds) {
            throw BuyInOutOfRangeException("잔액 부족 등 지갑 실패를 흉내낸다: userId=$userId")
        }
        toGameCalls += ToGameCall(userId, amount, tableId, memo)
    }

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        error("AdmitJoinRequestService 는 fromGame 을 부르지 않는다")
    }
}

private class AdmitJoinRequestFakeShowdownStore : ShowdownStore {
    private val store = mutableMapOf<Long, OpenShowdown>()
    override fun find(tableId: TableId): OpenShowdown? = store[tableId.value]
    override fun save(tableId: TableId, showdown: OpenShowdown) { store[tableId.value] = showdown }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

class AdmitJoinRequestServiceTest {

    private val tables = AdmitJoinRequestFakeTableRepository()
    private val handStore = AdmitJoinRequestFakeHandStore()
    private val showdownStore = AdmitJoinRequestFakeShowdownStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val fixedInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)
    private val revealTimeout: Duration = Duration.ofSeconds(10)
    private val walletTransfer = AdmitJoinRequestFakeWalletTransfer()
    private val handSettler = HandSettler(tables, handStore, showdownStore, eventPublisher, clock, nextHandDelay, revealTimeout)
    private val handStarter = HandStarter(tables, handStore, showdownStore, identityShuffler, handSettler, eventPublisher, clock, nextHandDelay)
    private val service = AdmitJoinRequestService(tables, handStore, walletTransfer, handStarter)

    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in buyIns) {
            table.sitDown(seatNo, userId = seatNo.toLong(), buyIn = Chips.of(buyIn))
        }
        table = tables.save(table)
        return table.id!!
    }

    @Test
    fun `핸드가 진행 중이면 참가 요청을 그대로 두고 아무 일도 하지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
            buttonSeatNo = 1,
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 2,
            smallBlind = Chips.of(100),
            bigBlind = Chips.of(200),
            shuffler = identityShuffler,
        )
        handStore.save(tableId, hand)

        var table = tables.findById(tableId)!!
        table.requestJoin(userId = 9001L, seatNo = 3, buyIn = Chips.of(8_000), postBlindImmediately = false, requestedAt = fixedInstant)
        table = tables.save(table)

        service.admit(AdmitJoinRequestCommand(tableId.value, 9001L))

        val savedTable = tables.findById(tableId)!!
        assertNull(savedTable.seatOf(9001L))
        assertEquals(0, walletTransfer.toGameCalls.size)
        assertTrue(savedTable.pendingJoinRequests().any { it.userId == 9001L })
    }

    @Test
    fun `핸드가 없으면 참가 요청을 좌석에 앉히고 지갑에서 바이인을 차감한다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        var table = tables.findById(tableId)!!
        table.requestJoin(userId = 9002L, seatNo = 3, buyIn = Chips.of(8_000), postBlindImmediately = false, requestedAt = fixedInstant)
        table = tables.save(table)

        service.admit(AdmitJoinRequestCommand(tableId.value, 9002L))

        val savedTable = tables.findById(tableId)!!
        assertEquals(9002L, savedTable.seatAt(3)?.userId)
        val call = walletTransfer.toGameCalls.single()
        assertEquals(9002L, call.userId)
        assertEquals(8_000L, call.amount)
        assertTrue(savedTable.pendingJoinRequests().none { it.userId == 9002L })
    }

    @Test
    fun `지갑 이체가 실패하면 예외를 던지고 좌석에 앉히지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        var table = tables.findById(tableId)!!
        table.requestJoin(userId = 9003L, seatNo = 3, buyIn = Chips.of(8_000), postBlindImmediately = false, requestedAt = fixedInstant)
        table = tables.save(table)

        val failingWallet = AdmitJoinRequestFakeWalletTransfer(failingUserIds = setOf(9003L))
        val failingService = AdmitJoinRequestService(tables, handStore, failingWallet, handStarter)

        assertFailsWith<BuyInOutOfRangeException> {
            failingService.admit(AdmitJoinRequestCommand(tableId.value, 9003L))
        }

        val savedTable = tables.findById(tableId)!!
        assertNull(savedTable.seatAt(3))
    }

    @Test
    fun `이미 다른 테이블에 앉은 사용자의 참가 요청은 AlreadySeatedException 을 던진다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        var otherTable = HoldemTable.create("other-table")
        otherTable = tables.save(otherTable)
        otherTable.sitDown(1, userId = 9004L, buyIn = Chips.of(8_000))
        tables.save(otherTable)

        var table = tables.findById(tableId)!!
        table.requestJoin(userId = 9004L, seatNo = 3, buyIn = Chips.of(8_000), postBlindImmediately = false, requestedAt = fixedInstant)
        table = tables.save(table)

        assertFailsWith<AlreadySeatedException> {
            service.admit(AdmitJoinRequestCommand(tableId.value, 9004L))
        }
    }
}
