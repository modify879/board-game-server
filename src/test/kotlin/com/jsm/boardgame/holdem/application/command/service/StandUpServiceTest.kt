package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
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

private class StandUpFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findByPendingJoinUserId(userId: Long): HoldemTable? = null

    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllPendingNextHandTableIds(): List<TableId> =
        stored.values.filter { it.nextHandAt != null }.mapNotNull { it.id }

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
        )
        stored[id.value] = saved
        return saved
    }
}

private class StandUpFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class StandUpFakeWalletTransfer : WalletTransfer {
    data class FromGameCall(val userId: Long, val amount: Long, val tableId: Long, val memo: String?)

    val fromGameCalls = mutableListOf<FromGameCall>()

    override fun toGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        error("StandUp 은 toGame 을 부르지 않는다")
    }

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        fromGameCalls += FromGameCall(userId, amount, tableId, memo)
    }
}

class StandUpServiceTest {

    private val tables = StandUpFakeHoldemTableRepository()
    private val handStore = StandUpFakeHandStore()
    private val walletTransfer = StandUpFakeWalletTransfer()
    private val eventPublisher = ApplicationEventPublisher { }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val nextHandDelay: Duration = Duration.ofSeconds(5)
    private val handSettler = HandSettler(tables, handStore, eventPublisher, clock, walletTransfer, nextHandDelay)
    private val handStarter = HandStarter(tables, handStore, Shuffler { it }, handSettler, eventPublisher, clock, nextHandDelay)
    private val service = StandUpService(tables, handStore, walletTransfer, handStarter)

    @Test
    fun `기립하면 좌석이 비고 스택이 지갑으로 돌아간다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(1, 1, Chips.of(8_000))
        val saved = tables.save(table)

        service.standUp(StandUpCommand(userId = 1))

        assertNull(tables.findById(saved.id!!)!!.seatAt(1))
        val call = walletTransfer.fromGameCalls.single()
        assertEquals(1L, call.userId)
        assertEquals(8_000L, call.amount)
        assertEquals(saved.id!!.value, call.tableId)
    }

    @Test
    fun `앉은 적 없는 사용자가 기립하면 NOT_SEATED`() {
        val e = assertFailsWith<NotSeatedException> {
            service.standUp(StandUpCommand(userId = 999))
        }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    @Test
    fun `핸드가 진행 중이면 기립할 수 없다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(1, 1, Chips.of(8_000))
        val saved = tables.save(table)
        handStore.save(
            saved.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it }),
        )

        val e = assertFailsWith<HandInProgressException> {
            service.standUp(StandUpCommand(userId = 1))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
        assertEquals(0, walletTransfer.fromGameCalls.size)
    }

    @Test
    fun `스택이 0 이면 기립은 되지만 지갑 이체는 불리지 않는다`() {
        val zeroStackSeat = Seat.reconstitute(seatNo = 1, userId = 1, stack = Chips.ZERO, presence = SeatPresence.SEATED)
        val table = HoldemTable.reconstitute(
            id = TableId(1),
            name = "테스트 테이블",
            smallBlind = HoldemTable.SMALL_BLIND,
            bigBlind = HoldemTable.BIG_BLIND,
            buttonSeatNo = null,
            seats = mapOf(1 to zeroStackSeat),
            version = 0,
        )
        tables.stored[1] = table

        service.standUp(StandUpCommand(userId = 1))

        assertEquals(0, walletTransfer.fromGameCalls.size)
        assertNull(tables.findById(TableId(1))!!.seatAt(1))
    }

    @Test
    fun `기립으로 후보가 2명 미만이 되면 nextHandAt 이 취소된다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(1, 1, Chips.of(8_000))
        table.sitDown(2, 2, Chips.of(8_000))
        table.scheduleNextHand(Instant.now(clock).plusSeconds(5))
        tables.save(table)

        service.standUp(StandUpCommand(userId = 1))

        assertNull(tables.findById(table.id!!)!!.nextHandAt)
    }

    @Test
    fun `기립 후에도 후보가 2명 이상이면 기존 nextHandAt 이 그대로 유지된다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(1, 1, Chips.of(8_000))
        table.sitDown(2, 2, Chips.of(8_000))
        table.sitDown(3, 3, Chips.of(8_000))
        val scheduledAt = Instant.now(clock).plusSeconds(5)
        table.scheduleNextHand(scheduledAt)
        tables.save(table)

        service.standUp(StandUpCommand(userId = 1))

        assertEquals(scheduledAt, tables.findById(table.id!!)!!.nextHandAt)
    }
}
