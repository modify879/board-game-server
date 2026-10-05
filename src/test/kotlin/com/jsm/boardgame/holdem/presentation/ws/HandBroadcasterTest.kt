package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.presentation.ws.payload.PayoutPublicView
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

private class HandBroadcasterFakeNextHandCountdown : NextHandCountdown {
    override fun restart(tableId: TableId) {}
    override fun cancel(tableId: TableId) {}
    override fun remaining(tableId: TableId): Duration? = null
}

class HandBroadcasterTest {

    private val last = LastHandResult(
        board = listOf("As", "Kd", "7c", "2h", "9s"),
        payouts = listOf(LastHandResult.Payout(1, 1000L, 2_000)),
        shownHands = emptyList(),
    )

    private fun publish(stored: LastHandResult?, hand: Hand?, eventLastResult: LastHandResult?): TablePublicView {
        val channel = HandBroadcasterFakeChannel()
        val broadcaster = HandBroadcaster(
            SimpMessagingTemplate(channel),
            HandBroadcasterFakeUserRegistry(),
            TableViewSequence(),
            HandBroadcasterFakeNextHandCountdown(),
            HandBroadcasterFakeLastHandResultStore(stored),
        )
        val table = HoldemTable.create("t")
        table.sitDown(1000L, Chips.of(1_000))

        broadcaster.publish(TableId(1L), table, hand, eventLastResult, 1L)

        return channel.payloads.filterIsInstance<TablePublicView>().single()
    }

    private fun publishWithoutHand(stored: LastHandResult?): TablePublicView = publish(stored, null, null)

    @Test
    fun `끝난 핸드의 브로드캐스트는 저장소가 아니라 이벤트가 실은 직전 결과를 쓴다`() {
        val hand = Hand.start(
            mapOf(1 to Chips.of(1_000), 2 to Chips.of(1_000)),
            buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2,
            smallBlind = Chips.of(100), bigBlind = Chips.of(200), shuffler = { it },
        )
        hand.act(1, BettingAction.Fold)
        val fromEvent = LastHandResult(listOf("2c"), listOf(LastHandResult.Payout(2, 2000L, 300)), emptyList())

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
