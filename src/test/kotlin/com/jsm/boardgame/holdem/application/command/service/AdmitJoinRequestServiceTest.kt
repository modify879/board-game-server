package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestResult
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AdmitJoinRequestFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    private fun reconstituteFrom(saved: HoldemTable): HoldemTable = HoldemTable.reconstitute(
        id = saved.id!!,
        name = saved.name,
        smallBlind = saved.smallBlind,
        bigBlind = saved.bigBlind,
        buttonSeatNo = saved.buttonSeatNo,
        seats = saved.occupiedSeats().associateBy { it.seatNo },
    )

    override fun findById(id: TableId): HoldemTable? = store[id.value]?.let { reconstituteFrom(it) }
    override fun findByUserId(userId: Long): HoldemTable? = store.values.find { it.seatOf(userId) != null }
    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }
    override fun findAllTableIds(): List<TableId> = store.values.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: TableId(nextId++)
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
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

private class AdmitJoinRequestFakeNextHandCountdown : NextHandCountdown {
    override fun restart(tableId: TableId) {}
    override fun cancel(tableId: TableId) {}
    override fun remaining(tableId: TableId): Duration? = null
}

class AdmitJoinRequestServiceTest {

    private val tables = AdmitJoinRequestFakeTableRepository()
    private val handStore = AdmitJoinRequestFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val walletTransfer = AdmitJoinRequestFakeWalletTransfer()
    private val countdown = AdmitJoinRequestFakeNextHandCountdown()
    private val handSettler = HandSettler(tables, handStore, eventPublisher, countdown)
    private val handStarter = HandStarter(tables, handStore, identityShuffler, handSettler, eventPublisher, countdown)
    private val service = AdmitJoinRequestService(tables, handStore, walletTransfer, handStarter)

    /** 특정 좌석 번호에 특정 버이인으로 미리 앉혀 둔다 — sitDown 이 더 이상 좌석을 고르지 않으므로
     *  reconstitute 로 원하는 좌석 배치를 직접 만든다. */
    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        val seats = buyIns.associate { (seatNo, buyIn) ->
            seatNo to Seat.reconstitute(seatNo, userId = seatNo.toLong(), stack = Chips.of(buyIn), presence = SeatPresence.SEATED)
        }
        val seeded = HoldemTable.reconstitute(
            id = table.id!!,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = seats,
        )
        tables.save(seeded)
        return table.id!!
    }

    @Test
    fun `핸드가 진행 중이면 참가를 막고 좌석·지갑을 건드리지 않는다`() {
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

        val result = service.admit(AdmitJoinRequestCommand(tableId.value, 9001L, buyIn = 8_000, postBlindImmediately = false))

        assertEquals(AdmitJoinRequestResult.Blocked, result)
        val savedTable = tables.findById(tableId)!!
        assertNull(savedTable.seatOf(9001L))
        assertTrue(walletTransfer.toGameCalls.isEmpty())
    }

    @Test
    fun `핸드가 없고 빈 좌석이 있으면 참가자를 좌석에 앉히고 지갑에서 바이인을 차감한다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        val result = service.admit(AdmitJoinRequestCommand(tableId.value, 9002L, buyIn = 8_000, postBlindImmediately = false))

        assertEquals(AdmitJoinRequestResult.Seated(3), result)
        val savedTable = tables.findById(tableId)!!
        assertEquals(9002L, savedTable.seatAt(3)?.userId)
        val call = walletTransfer.toGameCalls.single()
        assertEquals(9002L, call.userId)
        assertEquals(8_000L, call.amount)
    }

    @Test
    fun `지갑 이체가 실패하면 예외를 던지고 좌석에 앉히지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        val failingWallet = AdmitJoinRequestFakeWalletTransfer(failingUserIds = setOf(9003L))
        val failingService = AdmitJoinRequestService(tables, handStore, failingWallet, handStarter)

        assertFailsWith<BuyInOutOfRangeException> {
            failingService.admit(AdmitJoinRequestCommand(tableId.value, 9003L, buyIn = 8_000, postBlindImmediately = false))
        }

        assertNull(tables.findById(tableId)!!.seatOf(9003L))
    }

    @Test
    fun `이미 다른 테이블에 앉은 사용자의 참가는 AlreadySeatedException 을 던진다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        var otherTable = HoldemTable.create("other-table")
        otherTable = tables.save(otherTable)
        otherTable.sitDown(9004L, Chips.of(8_000))
        tables.save(otherTable)

        assertFailsWith<AlreadySeatedException> {
            service.admit(AdmitJoinRequestCommand(tableId.value, 9004L, buyIn = 8_000, postBlindImmediately = false))
        }
    }

    @Test
    fun `빈 좌석이 없으면 지갑을 부르지 않고 Blocked 를 반환한다`() {
        val tableId = tableWithSeats(
            1 to 10_000L, 2 to 10_000L, 3 to 10_000L, 4 to 10_000L, 5 to 10_000L,
            6 to 10_000L, 7 to 10_000L, 8 to 10_000L, 9 to 10_000L,
        )

        val result = service.admit(AdmitJoinRequestCommand(tableId.value, 9005L, buyIn = 8_000, postBlindImmediately = false))

        assertEquals(AdmitJoinRequestResult.Blocked, result)
        assertTrue(walletTransfer.toGameCalls.isEmpty())
    }
}
