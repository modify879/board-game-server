package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
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
        payouts = listOf(LastHandResult.Payout(1, 2_000)),
        shownHands = emptyList(),
    )

    private fun publishWithoutHand(stored: LastHandResult?): TablePublicView {
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

        broadcaster.publish(TableId(1L), table, null, 1L)

        return channel.payloads.single() as TablePublicView
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
