package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.port.FakeNextHandCountdown
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.TurnCountdown
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.presentation.ws.payload.PayoutPublicView
import com.jsm.boardgame.holdem.presentation.ws.payload.SeatPrivateView
import com.jsm.boardgame.holdem.presentation.ws.payload.TablePublicView
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUser
import org.springframework.messaging.simp.user.SimpUserRegistry
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class HandBroadcasterFakeChannel : MessageChannel {
    val payloads = mutableListOf<Any?>()
    override fun send(message: Message<*>): Boolean { payloads += message.payload; return true }
    override fun send(message: Message<*>, timeout: Long): Boolean = send(message)
}

private class HandBroadcasterFakeUserRegistry : SimpUserRegistry {
    override fun getUser(userName: String): SimpUser? = null
    override fun getUsers(): MutableSet<SimpUser> = mutableSetOf()
    override fun getUserCount(): Int = 0
    override fun findSubscriptions(matcher: org.springframework.messaging.simp.user.SimpSubscriptionMatcher): MutableSet<org.springframework.messaging.simp.user.SimpSubscription> = mutableSetOf()
}

private class HandBroadcasterFakeLastHandResultStore(private val result: LastHandResult?) : LastHandResultStore {
    override fun find(tableId: TableId): LastHandResult? = result
    override fun save(tableId: TableId, result: LastHandResult) {}
    override fun remove(tableId: TableId) {}
}

private class HandBroadcasterFakeTurnCountdown : TurnCountdown {
    override val turnTimeout: Duration = Duration.ofSeconds(30)
    override fun remaining(tableId: TableId): Duration? = null
}

private class HandBroadcasterThrowingChannel : MessageChannel {
    override fun send(message: Message<*>): Boolean = throw IllegalStateException("boom")
    override fun send(message: Message<*>, timeout: Long): Boolean = send(message)
}

class HandBroadcasterTest {

    private val last = LastHandResult(
        board = listOf("As", "Kd", "7c", "2h", "9s"),
        payouts = listOf(LastHandResult.Payout(1, UserId(1000L), 2_000)),
        shownHands = emptyList(),
    )

    private fun privateViewsOf(hand: Hand?, reserved: Boolean = false): List<SeatPrivateView> {
        val channel = HandBroadcasterFakeChannel()
        val broadcaster = HandBroadcaster(
            SimpMessagingTemplate(channel),
            HandBroadcasterFakeUserRegistry(),
            TableViewSequence(),
            FakeNextHandCountdown(),
            HandBroadcasterFakeTurnCountdown(),
            HandBroadcasterFakeLastHandResultStore(null),
        )
        val table = HoldemTable.create("t", TestTablePolicy)
        table.sitDown(UserId(1L), Chips.of(10_000))
        table.sitDown(UserId(2L), Chips.of(10_000))
        table.sitDown(UserId(3L), Chips.of(10_000))
        if (reserved) table.reserveChips(UserId(3L), Chips.of(500))

        broadcaster.publish(TableId(1L), table, hand, null, 1L)

        return channel.payloads.filterIsInstance<SeatPrivateView>()
    }

    private fun handOfSeats12(): Hand = Hand.start(
        mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)),
        buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2,
        smallBlind = TestTablePolicy.smallBlind, bigBlind = TestTablePolicy.bigBlind, shuffler = { it },
    )

    @Test
    fun `핸드가 없어도 착석자 전원에게 hand 가 null 인 개인 뷰를 보내고 칩 예약이 실린다`() {
        val views = privateViewsOf(hand = null, reserved = true)

        assertEquals(setOf(1, 2, 3), views.map { it.seatNo }.toSet())
        assertEquals(3, views.size)
        assertTrue(views.all { it.hand == null })
        assertEquals(500L, views.first { it.seatNo == 3 }.chipsReservation)
    }

    @Test
    fun `핸드 중에는 참가 좌석만 hand 를 받고 참가하지 않은 좌석은 hand 가 null 이다`() {
        val views = privateViewsOf(handOfSeats12())

        assertEquals(3, views.size)
        assertEquals(2, views.first { it.seatNo == 1 }.hand!!.holeCards.size)
        assertEquals(2, views.first { it.seatNo == 2 }.hand!!.holeCards.size)
        assertNull(views.first { it.seatNo == 3 }.hand)
    }

    @Test
    fun `끝난 핸드의 브로드캐스트도 참가 좌석에는 hand 를 싣고 availableActions 는 null 이다`() {
        val hand = handOfSeats12()
        hand.act(1, BettingAction.Fold)

        val views = privateViewsOf(hand)

        val dealt = views.first { it.seatNo == 1 }.hand!!
        assertEquals(2, dealt.holeCards.size)
        assertNull(dealt.availableActions)
        assertNull(views.first { it.seatNo == 3 }.hand)
    }

    private fun publish(stored: LastHandResult?, hand: Hand?, eventLastResult: LastHandResult?): TablePublicView {
        val channel = HandBroadcasterFakeChannel()
        val broadcaster = HandBroadcaster(
            SimpMessagingTemplate(channel),
            HandBroadcasterFakeUserRegistry(),
            TableViewSequence(),
            FakeNextHandCountdown(),
            HandBroadcasterFakeTurnCountdown(),
            HandBroadcasterFakeLastHandResultStore(stored),
        )
        val table = HoldemTable.create("t", TestTablePolicy)
        table.sitDown(UserId(1000L), Chips.of(10_000))

        broadcaster.publish(TableId(1L), table, hand, eventLastResult, 1L)

        return channel.payloads.filterIsInstance<TablePublicView>().single()
    }

    private fun publishWithoutHand(stored: LastHandResult?): TablePublicView = publish(stored, null, stored)

    @Test
    fun `전송 중 예외는 삼키고 던지지 않는다`() {
        val broadcaster = HandBroadcaster(
            SimpMessagingTemplate(HandBroadcasterThrowingChannel()),
            HandBroadcasterFakeUserRegistry(),
            TableViewSequence(),
            FakeNextHandCountdown(),
            HandBroadcasterFakeTurnCountdown(),
            HandBroadcasterFakeLastHandResultStore(null),
        )
        val table = HoldemTable.create("t", TestTablePolicy)

        broadcaster.publish(TableId(1L), table, null, null, 1L)
    }

    @Test
    fun `끝난 핸드의 브로드캐스트는 저장소가 아니라 이벤트가 실은 직전 결과를 쓴다`() {
        val hand = Hand.start(
            mapOf(1 to Chips.of(1_000), 2 to Chips.of(1_000)),
            buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2,
            smallBlind = TestTablePolicy.smallBlind, bigBlind = TestTablePolicy.bigBlind, shuffler = { it },
        )
        hand.act(1, BettingAction.Fold)
        val fromEvent = LastHandResult(listOf("2c"), listOf(LastHandResult.Payout(2, UserId(2000L), 300)), emptyList())

        val view = publish(stored = last, hand = hand, eventLastResult = fromEvent)

        assertEquals(listOf(PayoutPublicView(2, 2000L, 300)), view.result!!.payouts)
    }

    @Test
    fun `핸드 없이 브로드캐스트해도 직전 결과의 보드와 정산이 실린다`() {
        val view = publishWithoutHand(last)

        assertEquals(last.board, view.board)
        assertNotNull(view.result)
    }

    @Test
    fun `직전 결과가 없으면 핸드 없는 브로드캐스트에 결과가 없다`() {
        val view = publishWithoutHand(null)

        assertNull(view.result)
    }
}
