package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class ExpireTurnFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]?.let { copyOf(it) }

    override fun findByUserId(userId: Long): HoldemTable? =
        store.values.firstOrNull { it.seatOf(userId) != null }?.let { copyOf(it) }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null

    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        store.values.filter { it.nextHandAt != null }.mapNotNull { it.id }

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

private class ExpireTurnFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

private class ExpireTurnFakeWalletTransfer : WalletTransfer {
    data class FromGameCall(val userId: Long, val amount: Long, val tableId: Long, val memo: String?)

    val fromGameCalls = mutableListOf<FromGameCall>()

    override fun toGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        error("ExpireTurnService 는 toGame 을 부르지 않는다")
    }

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        fromGameCalls += FromGameCall(userId, amount, tableId, memo)
    }
}

class ExpireTurnServiceTest {

    private val tables = ExpireTurnFakeTableRepository()
    private val handStore = ExpireTurnFakeHandStore()
    private val walletTransfer = ExpireTurnFakeWalletTransfer()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)
    private val handSettler = HandSettler(tables, handStore, eventPublisher, clock, walletTransfer, nextHandDelay)
    private val service = ExpireTurnService(tables, handStore, handSettler, walletTransfer, eventPublisher)

    /** userId = seatNo * 1000 으로 대응시킨다. */
    private fun tableWithHand(vararg stacks: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in stacks) {
            table.sitDown(seatNo, userId = seatNo * 1000L, buyIn = Chips.of(buyIn))
        }
        table.moveButtonToNextOccupiedSeat()
        table = tables.save(table)

        val handStacks = stacks.associate { (seatNo, buyIn) -> seatNo to Chips.of(buyIn) }
        val buttonSeatNo = table.buttonSeatNo!!
        val seatNos = handStacks.keys.sorted()
        fun nextSeatNo(from: Int): Int = seatNos[(seatNos.indexOf(from) + 1) % seatNos.size]
        val (smallBlindSeatNo, bigBlindSeatNo) = if (seatNos.size == 2) {
            buttonSeatNo to nextSeatNo(buttonSeatNo)
        } else {
            val sb = nextSeatNo(buttonSeatNo)
            sb to nextSeatNo(sb)
        }
        val hand = Hand.start(handStacks, buttonSeatNo, smallBlindSeatNo, bigBlindSeatNo, table.smallBlind, table.bigBlind, identityShuffler)
        handStore.save(table.id!!, hand)
        return table.id!!
    }

    @Test
    fun `체크할 수 있으면 체크로 처리되고 좌석은 그대로 앉아 있다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val hand = handStore.find(tableId)!!
        // 프리플랍: 1(버튼) 콜, 2(SB) 콜 — 3(BB)에게 옵션이 넘어가면 체크 가능하다.
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Call)
        assertEquals(3, hand.toActSeatNo)
        assertEquals(true, hand.availableActionsFor(3)?.canCheck)

        service.expire(ExpireTurnCommand(tableId.value, 3))

        assertEquals(SeatStatus.ACTIVE, hand.statusOf(3))
        assertNotNull(tables.findById(tableId)!!.seatAt(3))
        assertEquals(0, walletTransfer.fromGameCalls.size)
    }

    @Test
    fun `체크할 수 없으면 폴드하고 즉시 기립하며 지갑에 남은 스택이 정확히 입금된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!! // 버튼(1) — 아직 아무것도 커밋하지 않았다.
        assertEquals(1, toAct)
        assertEquals(false, handStore.find(tableId)!!.availableActionsFor(toAct)?.canCheck)

        service.expire(ExpireTurnCommand(tableId.value, toAct))

        val hand = handStore.find(tableId)
        assertNotNull(hand)
        assertFalse(hand.isFinished)
        assertEquals(SeatStatus.FOLDED, hand.statusOf(toAct))
        assertNull(tables.findById(tableId)!!.seatAt(toAct))
        val credited = walletTransfer.fromGameCalls.single()
        assertEquals(1000L, credited.userId)
        assertEquals(10_000L, credited.amount)
        assertEquals(tableId.value, credited.tableId)
    }

    @Test
    fun `폴드 시점까지 이미 커밋한 칩은 팟에 남고 지갑에는 잔여 스택만 입금된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val hand = handStore.find(tableId)!!
        hand.act(1, BettingAction.Call) // 버튼이 BB 까지 콜
        assertEquals(2, hand.toActSeatNo) // SB — 이미 100 을 커밋했지만 BB 에는 못 미친다.
        assertEquals(false, hand.availableActionsFor(2)?.canCheck)

        service.expire(ExpireTurnCommand(tableId.value, 2))

        assertEquals(SeatStatus.FOLDED, hand.statusOf(2))
        assertNull(tables.findById(tableId)!!.seatAt(2))
        val credited = walletTransfer.fromGameCalls.single()
        assertEquals(9_900L, credited.amount) // 10,000 - 100(SB) : 커밋한 100은 팟에 남는다.
        assertEquals(Chips.of(500), hand.potTotal()) // 버튼 200(콜) + SB 100 + BB 200
    }

    @Test
    fun `차례가 아닌 좌석의 만료 요청은 아무 일도 하지 않는다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!
        val notToAct = (setOf(1, 2, 3) - toAct).first()

        service.expire(ExpireTurnCommand(tableId.value, notToAct))

        val hand = handStore.find(tableId)
        assertNotNull(hand)
        assertEquals(toAct, hand.toActSeatNo)
        assertEquals(SeatStatus.ACTIVE, hand.statusOf(notToAct))
        assertEquals(0, walletTransfer.fromGameCalls.size)
    }

    @Test
    fun `핸드가 없으면 조용히 끝난다`() {
        var table = HoldemTable.create("no-hand")
        table = tables.save(table)
        table.sitDown(1, userId = 1000L, buyIn = Chips.of(10_000))
        tables.save(table)

        service.expire(ExpireTurnCommand(table.id!!.value, 1))

        assertNull(handStore.find(table.id!!))
    }

    @Test
    fun `테이블이 없으면 조용히 끝난다`() {
        service.expire(ExpireTurnCommand(999, 1))
        // 예외 없이 반환되면 충분하다.
    }

    @Test
    fun `만료 폴드로 핸드가 끝나면 폴드한 좌석은 이미 지갑에 반영되고 남은 좌석과 합쳐 칩이 보존된다`() {
        val tableId = tableWithHand(1 to 10_000L, 2 to 10_000L)
        val toAct = handStore.find(tableId)!!.toActSeatNo!!

        service.expire(ExpireTurnCommand(tableId.value, toAct))

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        assertNull(savedTable.seatAt(toAct))
        val winnerSeatNo = (setOf(1, 2) - toAct).first()
        val winnerStack = savedTable.seatAt(winnerSeatNo)!!.stack
        val credited = walletTransfer.fromGameCalls.single().amount
        assertEquals(Chips.of(20_000), winnerStack + Chips.of(credited))
    }
}
