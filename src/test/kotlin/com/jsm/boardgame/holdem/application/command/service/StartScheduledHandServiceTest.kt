package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeNextHandCountdown
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.port.FakeLastHandResultStore
import com.jsm.boardgame.holdem.domain.model.placeButtonAtFirstSeat
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.port.FakeFoldWinnerCards
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

private class StartScheduledHandFakeWalletTransfer : WalletTransfer {
    var touched = false

    override fun toGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) { touched = true }
    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) { touched = true }
}

class StartScheduledHandServiceTest {

    private val tables = StartScheduledHandFakeTableRepository()
    private val handStore = FakeHandStore()
    private val identityShuffler = Shuffler { it }
    private val eventPublisher = ApplicationEventPublisher { }
    private val walletTransfer = StartScheduledHandFakeWalletTransfer()
    private val countdown = FakeNextHandCountdown()
    private val handStarter = HandStarter(tables, handStore, FakeLastHandResultStore(), FakeFoldWinnerCards(), identityShuffler, eventPublisher, countdown)
    private val service = StartScheduledHandService(
        tables, handStore, handStarter, TransactionOperations.withoutTransaction(),
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
        table.placeButtonAtFirstSeat()
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
    fun `핸드가 시작돼도 지갑에 들어온 돈은 스택에 합쳐지지 않는다 — 칩 추가만 지갑에서 옮긴다`() {
        val tableId = tableWithSeats(1 to 10_000L, 2 to 10_000L)

        service.start(StartScheduledHandCommand(tableId.value))

        assertTrue(handStore.find(tableId) != null)
        assertFalse(walletTransfer.touched)
        assertEquals(Chips.of(10_000), tables.findById(tableId)!!.seatAt(2)!!.stack)
    }
}

