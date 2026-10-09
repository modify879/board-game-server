package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StartScheduledHandFakeTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]
    override fun findByUserId(userId: UserId): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findAllSeatedUserIds(): List<UserId> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }
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

private class StartScheduledHandFakeWalletTransfer : WalletTransfer {
    /** 지갑 잔액. allToGame 은 잔액 전부를 돌려주고 0 으로 만든다. */
    val balances = mutableMapOf<Long, Long>()
    val allToGameCalls = mutableListOf<Long>()
    var failingUserId: Long? = null

    override fun allToGame(userId: UserId, tableId: TableId, memo: String?): Long {
        allToGameCalls += userId.value
        if (userId.value == failingUserId) error("지갑 행 락 대기 시간 초과")
        return balances.remove(userId.value) ?: 0
    }

    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        error("HandStarter 는 fromGame 을 부르지 않는다")
    }
}

private class StartScheduledHandFakeWalletBalance(private val wallet: StartScheduledHandFakeWalletTransfer) : WalletBalance {
    override fun balanceOf(userId: UserId): Long = wallet.balances[userId.value] ?: 0
}

class StartScheduledHandServiceTest {

    private val tables = StartScheduledHandFakeTableRepository()
    private val handStore = StartScheduledHandFakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val walletTransfer = StartScheduledHandFakeWalletTransfer()
    private val countdown = StartScheduledHandFakeNextHandCountdown()
    private val handStarter = HandStarter(tables, handStore, StartScheduledHandServiceFakeLastHandResultStore(), identityShuffler, eventPublisher, countdown)
    private val tableSeatsTopUp = TableSeatsTopUp(
        tables, StartScheduledHandFakeWalletBalance(walletTransfer), SeatTopUper(tables, handStore, walletTransfer),
    )
    private val service = StartScheduledHandService(
        tables, handStore, handStarter, tableSeatsTopUp, TransactionOperations.withoutTransaction(),
    )

    private fun tableWithSeats(vararg buyIns: Pair<Int, Long>): TableId {
        var table = HoldemTable.create("test-table")
        table = tables.save(table)
        for ((seatNo, buyIn) in buyIns) {
            table.sitDown(userId = UserId(seatNo.toLong()), buyIn = Chips.of(buyIn))
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

    @Test
    fun `핸드가 시작되면 착석 중 지갑에 들어온 잔액이 그 좌석 스택에 합쳐진다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)
        walletTransfer.balances[2L] = 5_000

        service.start(StartScheduledHandCommand(tableId.value))

        assertEquals(Chips.of(15_000), tables.findById(tableId)!!.seatAt(2)!!.stack)
        assertTrue(handStore.find(tableId)!!.stackOf(2).amount > 10_000L) // 시작 트랜잭션이 합쳐진 스택을 읽었다
    }

    @Test
    fun `한 좌석의 지갑 합산이 던져도 핸드는 시작되고 다른 좌석은 합쳐진다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L, 3 to 10_000L)
        walletTransfer.balances[1L] = 1_000
        walletTransfer.balances[3L] = 3_000
        walletTransfer.failingUserId = 1L

        service.start(StartScheduledHandCommand(tableId.value))

        assertTrue(handStore.find(tableId) != null)
        val table = tables.findById(tableId)!!
        assertEquals(Chips.of(10_000), table.seatAt(1)!!.stack)
        assertEquals(Chips.of(13_000), table.seatAt(3)!!.stack)
    }

    @Test
    fun `지갑 잔액이 0이면 이체를 부르지 않는다 - 지갑 락을 잡지 않는다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        service.start(StartScheduledHandCommand(tableId.value))

        assertTrue(walletTransfer.allToGameCalls.isEmpty())
        assertTrue(handStore.find(tableId) != null)
    }
}

private class StartScheduledHandServiceFakeLastHandResultStore : LastHandResultStore {
    val store = mutableMapOf<Long, LastHandResult>()
    override fun find(tableId: TableId): LastHandResult? = store[tableId.value]
    override fun save(tableId: TableId, result: LastHandResult) { store[tableId.value] = result }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}
