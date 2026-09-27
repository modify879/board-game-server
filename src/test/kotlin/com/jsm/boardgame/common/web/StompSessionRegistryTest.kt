package com.jsm.boardgame.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.net.InetSocketAddress
import java.net.URI
import java.security.Principal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private class RegistryFakeWebSocketSession(private val sessionId: String) : WebSocketSession {
    var closeStatus: CloseStatus? = null
    private var open = true

    override fun getId(): String = sessionId
    override fun getUri(): URI? = null
    override fun getHandshakeHeaders(): HttpHeaders = HttpHeaders.EMPTY
    override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()
    override fun getPrincipal(): Principal? = null
    override fun getLocalAddress(): InetSocketAddress? = null
    override fun getRemoteAddress(): InetSocketAddress? = null
    override fun getAcceptedProtocol(): String? = null
    override fun setTextMessageSizeLimit(messageSizeLimit: Int) {}
    override fun getTextMessageSizeLimit(): Int = 0
    override fun setBinaryMessageSizeLimit(messageSizeLimit: Int) {}
    override fun getBinaryMessageSizeLimit(): Int = 0
    override fun getExtensions(): MutableList<WebSocketExtension> = mutableListOf()
    override fun sendMessage(message: WebSocketMessage<*>) {}
    override fun isOpen(): Boolean = open
    override fun close() {
        open = false
    }

    override fun close(status: CloseStatus) {
        open = false
        closeStatus = status
    }
}

private class RegistryFakeScheduledFuture : ScheduledFuture<Any> {
    var cancelled = false

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelled = true
        return true
    }

    override fun isCancelled(): Boolean = cancelled
    override fun isDone(): Boolean = cancelled
    override fun get(): Any = throw UnsupportedOperationException("not used in this test")
    override fun get(timeout: Long, unit: TimeUnit): Any = throw UnsupportedOperationException("not used in this test")
    override fun getDelay(unit: TimeUnit): Long = throw UnsupportedOperationException("not used in this test")
    override fun compareTo(other: Delayed): Int = throw UnsupportedOperationException("not used in this test")
}

private class RegistryFakeTaskScheduler : TaskScheduler {
    class ScheduledCall(val task: Runnable, val time: Instant, val future: RegistryFakeScheduledFuture)

    val scheduled = mutableListOf<ScheduledCall>()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = RegistryFakeScheduledFuture()
        scheduled += ScheduledCall(task, startTime, future)
        return future
    }

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> =
        throw UnsupportedOperationException("not used in this test")

    override fun scheduleAtFixedRate(task: Runnable, startTime: Instant, period: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException("not used in this test")

    override fun scheduleAtFixedRate(task: Runnable, period: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException("not used in this test")

    override fun scheduleWithFixedDelay(task: Runnable, startTime: Instant, delay: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException("not used in this test")

    override fun scheduleWithFixedDelay(task: Runnable, delay: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException("not used in this test")
}

class StompSessionRegistryTest {
    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val scheduler = RegistryFakeTaskScheduler()
    private val registry = StompSessionRegistry(clock, scheduler)

    @Test
    fun `register 는 만료 시각에 세션을 닫도록 예약하고, 그 시각에 예약된 작업이 실행되면 세션이 정책 위반 사유로 닫힌다`() {
        val session = RegistryFakeWebSocketSession("session-1")
        registry.registerSession("session-1", session)
        val expiresAt = fixedInstant.plusSeconds(60)

        registry.register("session-1", "jti-1", 1L, expiresAt)

        assertThat(scheduler.scheduled).hasSize(1)
        assertThat(scheduler.scheduled[0].time).isEqualTo(expiresAt)

        scheduler.scheduled[0].task.run()

        assertThat(session.closeStatus?.code).isEqualTo(CloseStatus.POLICY_VIOLATION.code)
        assertThat(session.closeStatus?.reason).isEqualTo("AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `closeAllOf 는 같은 userId 로 연결된 세션만 닫고 다른 사용자의 세션은 그대로 둔다`() {
        val session1 = RegistryFakeWebSocketSession("session-1")
        val session2 = RegistryFakeWebSocketSession("session-2")
        registry.registerSession("session-1", session1)
        registry.registerSession("session-2", session2)
        registry.register("session-1", "jti-1", 1L, fixedInstant.plusSeconds(60))
        registry.register("session-2", "jti-2", 2L, fixedInstant.plusSeconds(60))

        registry.closeAllOf(1L)

        // 실제 닫기는 캐치스레드가 아니라 스케줄러에 위임된다 — 즉시 예약된 작업을 직접 실행해야 닫힌다.
        assertThat(scheduler.scheduled[0].future.cancelled).isTrue()
        assertThat(scheduler.scheduled).hasSize(3)
        scheduler.scheduled[2].task.run()

        assertThat(session1.closeStatus?.code).isEqualTo(CloseStatus.POLICY_VIOLATION.code)
        assertThat(session1.closeStatus?.reason).isEqualTo("AUTHENTICATION_REQUIRED")

        assertThat(session2.closeStatus).isNull()
        assertThat(scheduler.scheduled[1].future.cancelled).isFalse()
    }

    @Test
    fun `register 를 같은 세션에 두 번 호출하면 첫 예약이 취소되고 새 예약으로 바뀐다`() {
        registry.registerSession("session-1", RegistryFakeWebSocketSession("session-1"))
        registry.register("session-1", "jti-1", 1L, fixedInstant.plusSeconds(60))

        registry.register("session-1", "jti-2", 1L, fixedInstant.plusSeconds(120))

        assertThat(scheduler.scheduled[0].future.cancelled).isTrue()
        assertThat(scheduler.scheduled).hasSize(2)
        assertThat(scheduler.scheduled[1].time).isEqualTo(fixedInstant.plusSeconds(120))
    }

    @Test
    fun `세션이 닫히면 소켓 매핑도 함께 지워진다`() {
        registry.registerSession("session-1", RegistryFakeWebSocketSession("session-1"))
        registry.register("session-1", "jti-1", 1L, fixedInstant.plusSeconds(60))

        scheduler.scheduled[0].task.run()

        assertThat(registry.sessionIds()).doesNotContain("session-1")
    }

    @Test
    fun `replaceToken 으로 jti 가 바뀐 뒤에도 closeAllOf 는 userId 로 그 세션을 찾아 닫는다`() {
        val session = RegistryFakeWebSocketSession("session-1")
        registry.registerSession("session-1", session)
        registry.register("session-1", "jti-A", 1L, fixedInstant.plusSeconds(60))
        registry.replaceToken("session-1", "jti-B", fixedInstant.plusSeconds(120))

        registry.closeAllOf(1L)
        scheduler.scheduled.last().task.run()

        assertThat(session.closeStatus?.code).isEqualTo(CloseStatus.POLICY_VIOLATION.code)
        assertThat(session.closeStatus?.reason).isEqualTo("AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `closeAfterGrace 는 마감이 더 이르면 당기고, grace 뒤가 원래 마감보다 늦으면 그대로 둔다`() {
        registry.registerSession("session-1", RegistryFakeWebSocketSession("session-1"))
        registry.register("session-1", "jti-1", 1L, fixedInstant.plus(Duration.ofMinutes(10)))

        registry.closeAfterGrace("jti-1", Duration.ofMinutes(1))

        assertThat(scheduler.scheduled[0].future.cancelled).isTrue()
        assertThat(scheduler.scheduled).hasSize(2)
        assertThat(scheduler.scheduled[1].time).isEqualTo(fixedInstant.plus(Duration.ofMinutes(1)))
    }

    @Test
    fun `closeAfterGrace 는 grace 뒤가 원래 마감보다 늦으면 아무 것도 하지 않는다`() {
        registry.registerSession("session-2", RegistryFakeWebSocketSession("session-2"))
        registry.register("session-2", "jti-2", 2L, fixedInstant.plusSeconds(5))

        registry.closeAfterGrace("jti-2", Duration.ofMinutes(1))

        assertThat(scheduler.scheduled).hasSize(1)
        assertThat(scheduler.scheduled[0].future.cancelled).isFalse()
    }

    @Test
    fun `replaceToken 은 기존 예약을 취소하고 새 만료 시각으로 다시 예약하며, 이후 옛 jti 로의 closeAfterGrace 는 더 이상 효과가 없다`() {
        registry.registerSession("session-1", RegistryFakeWebSocketSession("session-1"))
        registry.register("session-1", "old", 1L, fixedInstant.plusSeconds(60))
        val newExpiresAt = fixedInstant.plus(Duration.ofMinutes(5))

        registry.replaceToken("session-1", "new", newExpiresAt)

        assertThat(scheduler.scheduled[0].future.cancelled).isTrue()
        assertThat(scheduler.scheduled).hasSize(2)
        assertThat(scheduler.scheduled[1].time).isEqualTo(newExpiresAt)

        registry.closeAfterGrace("old", Duration.ofSeconds(1))

        assertThat(scheduler.scheduled).hasSize(2)
        assertThat(scheduler.scheduled[1].future.cancelled).isFalse()
    }

    @Test
    fun `onSessionDisconnect 는 그 세션의 예약된 종료 작업을 취소한다`() {
        registry.registerSession("session-1", RegistryFakeWebSocketSession("session-1"))
        registry.register("session-1", "jti-1", 1L, fixedInstant.plusSeconds(60))
        val message = MessageBuilder.withPayload(ByteArray(0)).build()
        val event = SessionDisconnectEvent(this, message, "session-1", CloseStatus.NORMAL)

        registry.onSessionDisconnect(event)

        assertThat(scheduler.scheduled[0].future.cancelled).isTrue()
    }
}
