package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.support.SeatChipsReservationApplier
import com.jsm.boardgame.holdem.application.command.usecase.ApplyChipsReservationsCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.repository.FakeHandStore
import com.jsm.boardgame.holdem.domain.repository.FakeCopyingHoldemTableRepository
import com.jsm.boardgame.holdem.application.port.FakeNextHandCountdown
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ApplyChipsFakeWallet : WalletBalance, WalletTransfer {
    data class ToGameCall(val userId: UserId, val amount: Long, val tableId: TableId)

    var balance = 0L
    var failToGame = false
    val toGameCalls = mutableListOf<ToGameCall>()

    override fun balanceOf(userId: UserId): Long = balance
    override fun toGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        if (failToGame) error("지갑 이체 실패")
        toGameCalls += ToGameCall(userId, amount, tableId)
        balance -= amount
    }
    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) = error("fromGame 은 부르지 않는다")
}

/** [failUser] 좌석은 적용도 버리기도 던진다. */
private class ApplyChipsFailingApplier(
    private val failUser: UserId,
    tables: FakeCopyingHoldemTableRepository,
    handStore: FakeHandStore,
    wallet: ApplyChipsFakeWallet,
    departures: FakeRecentDepartures,
    countdown: FakeNextHandCountdown,
    events: ApplicationEventPublisher,
) : SeatChipsReservationApplier(tables, handStore, wallet, wallet, departures, countdown, events) {
    override fun apply(tableId: TableId, userId: UserId) {
        if (userId == failUser) error("적용 실패")
        super.apply(tableId, userId)
    }

    override fun drop(tableId: TableId, userId: UserId) {
        if (userId == failUser) error("버리기 실패")
        super.drop(tableId, userId)
    }
}

class ApplyChipsReservationsServiceTest {

    private val tables = FakeCopyingHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val wallet = ApplyChipsFakeWallet()
    private val departures = FakeRecentDepartures()
    private val countdown = FakeNextHandCountdown()
    private val events = mutableListOf<Any>()
    private val applier = SeatChipsReservationApplier(
        tables, handStore, wallet, wallet, departures, countdown, ApplicationEventPublisher { events += it },
    )
    private val service = ApplyChipsReservationsService(tables, handStore, applier)

    /** 좌석 번호 → (스택, 예약). 사용자 id 는 좌석 번호 × 1000. */
    private fun table(vararg seats: Triple<Int, Long, Long?>): TableId {
        val saved = tables.save(
            HoldemTable.reconstitute(
                id = TableId(1), name = "t", policy = TestTablePolicy, buttonSeatNo = 1,
                seats = seats.associate { (no, stack, reserved) ->
                    no to Seat.reconstitute(
                        no, UserId(no * 1000L), Chips.of(stack), SeatPresence.SEATED,
                        chipsReservation = reserved?.let { Chips.of(it) },
                    )
                },
            ),
        )
        return saved.id!!
    }

    private fun apply(tableId: TableId) = service.apply(ApplyChipsReservationsCommand(tableId.value))

    private fun seat(tableId: TableId, no: Int) = tables.findById(tableId)!!.seatAt(no)

    @Test
    fun `예약한 만큼 지갑에서 옮겨 스택에 더하고 예약을 지우고 브로드캐스트하며 카운트다운을 다시 센다`() {
        val tableId = table(Triple(1, 10_000, 3_000L), Triple(2, 10_000, null))
        wallet.balance = 50_000

        apply(tableId)

        assertEquals(Chips.of(13_000), seat(tableId, 1)!!.stack)
        assertNull(seat(tableId, 1)!!.chipsReservation)
        assertEquals(listOf(ApplyChipsFakeWallet.ToGameCall(UserId(1000), 3_000, tableId)), wallet.toGameCalls)
        assertEquals(1, events.filterIsInstance<HandBroadcastRequested>().size)
        assertEquals(listOf(tableId), countdown.restarted)
    }

    @Test
    fun `핸드가 끝난 뒤의 스택 기준 한도로 줄인다`() {
        val tableId = table(Triple(1, 16_000, 5_000L), Triple(2, 10_000, null))
        wallet.balance = 50_000

        apply(tableId)

        assertEquals(Chips.of(20_000), seat(tableId, 1)!!.stack)
        assertEquals(4_000L, wallet.toGameCalls.single().amount)
    }

    @Test
    fun `지갑 잔액으로 줄이고 100 단위로 내림한다`() {
        val tableId = table(Triple(1, 10_000, 3_000L), Triple(2, 10_000, null))
        wallet.balance = 1_050

        apply(tableId)

        assertEquals(Chips.of(11_000), seat(tableId, 1)!!.stack)
        assertEquals(1_000L, wallet.toGameCalls.single().amount)
    }

    @Test
    fun `더할 수 있는 칩이 0 이면 이체 없이 예약만 지운다`() {
        val tableId = table(Triple(1, 20_000, 3_000L), Triple(2, 10_000, null))
        wallet.balance = 50_000

        apply(tableId)

        assertTrue(wallet.toGameCalls.isEmpty())
        assertEquals(Chips.of(20_000), seat(tableId, 1)!!.stack)
        assertNull(seat(tableId, 1)!!.chipsReservation)
    }

    @Test
    fun `예약으로 채워진 0칩 좌석은 앉아 있고 카운트다운을 다시 센다`() {
        val tableId = table(Triple(1, 0, 3_000L), Triple(2, 10_000, null))
        wallet.balance = 50_000

        apply(tableId)

        assertEquals(Chips.of(3_000), seat(tableId, 1)!!.stack)
        assertEquals(listOf(tableId), countdown.restarted)
        assertTrue(departures.recorded.isEmpty())
    }

    @Test
    fun `채울 수 없는 0칩 좌석은 지갑 없이 기립하고 의무를 0 으로 덮는다`() {
        val tableId = table(Triple(1, 0, 3_000L), Triple(2, 10_000, null))
        wallet.balance = 0

        apply(tableId)

        assertNull(seat(tableId, 1))
        assertTrue(wallet.toGameCalls.isEmpty())
        assertEquals(listOf(UserId(1000) to Chips.ZERO), departures.recorded)
        assertTrue(countdown.restarted.isEmpty())
    }

    @Test
    fun `이체가 실패하면 예약을 버리고 0칩 좌석은 기립시킨다`() {
        val tableId = table(Triple(1, 0, 3_000L), Triple(2, 10_000, 1_000L), Triple(3, 0, null))
        wallet.balance = 50_000
        wallet.failToGame = true

        apply(tableId)

        assertNull(seat(tableId, 1)) // 0칩 + 실패 → 기립
        assertNotNull(seat(tableId, 2))
        assertEquals(Chips.of(10_000), seat(tableId, 2)!!.stack) // 스택은 그대로
        assertNull(seat(tableId, 2)!!.chipsReservation) // 예약은 버렸다
        assertNotNull(seat(tableId, 3)) // 예약 없는 좌석은 건드리지 않는다
    }

    @Test
    fun `핸드가 진행 중이면 아무것도 하지 않는다`() {
        val tableId = table(Triple(1, 10_000, 3_000L), Triple(2, 10_000, null))
        handStore.save(
            tableId,
            Hand.start(mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, TestTablePolicy.smallBlind, TestTablePolicy.bigBlind, Shuffler { it }),
        )
        wallet.balance = 50_000

        apply(tableId)

        assertTrue(wallet.toGameCalls.isEmpty())
        assertEquals(Chips.of(3_000), seat(tableId, 1)!!.chipsReservation)
    }

    @Test
    fun `버리기마저 던져도 다음 좌석의 예약은 적용한다`() {
        val tableId = table(Triple(1, 10_000, 3_000L), Triple(2, 10_000, 3_000L))
        wallet.balance = 50_000
        val failing = ApplyChipsFailingApplier(UserId(1000), tables, handStore, wallet, departures, countdown, ApplicationEventPublisher { events += it })

        ApplyChipsReservationsService(tables, handStore, failing).apply(ApplyChipsReservationsCommand(tableId.value))

        assertEquals(Chips.of(13_000), seat(tableId, 2)!!.stack)
        assertEquals(listOf(ApplyChipsFakeWallet.ToGameCall(UserId(2000), 3_000, tableId)), wallet.toGameCalls)
    }
}
