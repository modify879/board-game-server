package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

private class ClosedFakeFuture : ScheduledFuture<Any?> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = false
    override fun get(): Any? = null
    override fun get(timeout: Long, unit: TimeUnit?): Any? = null
    override fun compareTo(other: Delayed?): Int = 0
    override fun getDelay(unit: TimeUnit): Long = 0
}

private class ClosedRecordingScheduler : TaskScheduler {
    val tasks = ArrayDeque<Runnable>()
    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> { tasks += task; return ClosedFakeFuture() }
    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleAtFixedRate(task: Runnable, startTime: Instant, period: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleAtFixedRate(task: Runnable, period: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleWithFixedDelay(task: Runnable, startTime: Instant, delay: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleWithFixedDelay(task: Runnable, delay: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
}

class TableClosedBroadcasterTest {

    private val scheduler = ClosedRecordingScheduler()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun broadcaster(channel: ClosedFlakyChannel) =
        TableClosedBroadcaster(SimpMessagingTemplate(channel), scheduler, clock)

    @Test
    fun `이벤트 스레드에서는 전송하지 않고 스케줄러로 넘긴다`() {
        val channel = ClosedFlakyChannel(0)

        broadcaster(channel).onTableClosed(TableClosed(TableId(1)))

        assertEquals(0, channel.sent.size)
        scheduler.tasks.removeFirst().run()
        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `첫 전송이 실패하면 재시도로 전송된다`() {
        val channel = ClosedFlakyChannel(1)

        broadcaster(channel).onTableClosed(TableClosed(TableId(1)))
        scheduler.tasks.removeFirst().run()
        assertEquals(0, channel.sent.size)
        scheduler.tasks.removeFirst().run()

        assertEquals(1, channel.sent.size)
        assertEquals(0, scheduler.tasks.size)
    }
}
