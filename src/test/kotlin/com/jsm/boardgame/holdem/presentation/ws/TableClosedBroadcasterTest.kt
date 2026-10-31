package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessagingTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

private class ClosedFlakyChannel(private var failures: Int) : MessageChannel {
    val sent = mutableListOf<Message<*>>()
    override fun send(message: Message<*>): Boolean = send(message, -1)
    override fun send(message: Message<*>, timeout: Long): Boolean {
        if (failures-- > 0) throw IllegalStateException("boom")
        sent += message
        return true
    }
}

class TableClosedBroadcasterTest {

    private fun broadcaster(channel: ClosedFlakyChannel) = TableClosedBroadcaster(SimpMessagingTemplate(channel))

    @Test
    fun `닫힘 커밋 후 공개 토픽에 바로 전송한다`() {
        val channel = ClosedFlakyChannel(0)

        broadcaster(channel).onTableClosed(TableClosed(TableId(1)))

        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `전송이 실패해도 예외를 올리지 않는다`() {
        val channel = ClosedFlakyChannel(1)

        broadcaster(channel).onTableClosed(TableClosed(TableId(1)))

        assertEquals(0, channel.sent.size)
    }
}
