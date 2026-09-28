package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.CommonErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
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

private class AuthRefreshFakeWebSocketSession(private val sessionId: String) : WebSocketSession {
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
    override fun isOpen(): Boolean = true
    override fun close() {}
    override fun close(status: CloseStatus) {}
}

private class AuthRefreshFakeScheduledFuture : ScheduledFuture<Any> {
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

private class AuthRefreshFakeTaskScheduler : TaskScheduler {
    class ScheduledCall(val task: Runnable, val time: Instant, val future: AuthRefreshFakeScheduledFuture)

    val scheduled = mutableListOf<ScheduledCall>()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        val future = AuthRefreshFakeScheduledFuture()
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

private class AuthRefreshFakePrincipal(private val name: String) : Principal {
    override fun getName(): String = name
}

/** subject/jti/exp 을 자유롭게 세팅해 돌려주고, "invalid" 토큰 문자열에는 JwtException 을 던진다. */
private class AuthRefreshFakeJwtDecoder(
    private val tokensBySubject: Map<String, Jwt>,
) : JwtDecoder {
    override fun decode(token: String): Jwt =
        tokensBySubject[token] ?: throw JwtException("invalid token: $token")
}

private fun fakeJwt(subject: String, jti: String, expiresAt: Instant): Jwt =
    Jwt.withTokenValue("token-$subject-$jti")
        .header("alg", "none")
        .subject(subject)
        .jti(jti)
        .expiresAt(expiresAt)
        .build()

class StompAuthRefreshControllerTest {
    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val scheduler = AuthRefreshFakeTaskScheduler()
    private val registry = StompSessionRegistry(clock, scheduler)

    @Test
    fun `호출한 세션의 principal 과 subject 가 같은 새 토큰이면 갱신에 성공하고 새 만료 시각으로 다시 예약된다`() {
        registry.registerSession("session-1", AuthRefreshFakeWebSocketSession("session-1"))
        registry.register("session-1", "old-jti", 1L, fixedInstant.plusSeconds(60))
        val newExpiresAt = fixedInstant.plus(Duration.ofMinutes(5))
        val newToken = fakeJwt(subject = "1", jti = "new-jti", expiresAt = newExpiresAt)
        val decoder = AuthRefreshFakeJwtDecoder(mapOf("valid-token" to newToken))
        val controller = StompAuthRefreshController(decoder, registry, clock)

        val reply = controller.refresh(
            RefreshTokenPayload("valid-token"),
            AuthRefreshFakePrincipal("1"),
            "session-1",
        )

        assertThat(reply.result).isEqualTo("OK")
        assertThat(reply.expiresAt).isEqualTo(newExpiresAt)
        assertThat(reply.expiresInMs).isEqualTo(Duration.ofMinutes(5).toMillis())
        assertThat(scheduler.scheduled).hasSize(2)
        assertThat(scheduler.scheduled[0].future.cancelled).isTrue()
        assertThat(scheduler.scheduled[1].time).isEqualTo(newExpiresAt)
    }

    @Test
    fun `JwtDecoder 검증에 실패한 토큰이면 AUTHENTICATION_REQUIRED 로 거부하고 기존 예약을 건드리지 않는다`() {
        registry.registerSession("session-1", AuthRefreshFakeWebSocketSession("session-1"))
        registry.register("session-1", "old-jti", 1L, fixedInstant.plusSeconds(60))
        val decoder = AuthRefreshFakeJwtDecoder(emptyMap())
        val controller = StompAuthRefreshController(decoder, registry, clock)

        val reply = controller.refresh(
            RefreshTokenPayload("invalid-token"),
            AuthRefreshFakePrincipal("1"),
            "session-1",
        )

        assertThat(reply.result).isEqualTo("ERROR")
        assertThat(reply.errorCode).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED.code)
        assertThat(scheduler.scheduled).hasSize(1)
        assertThat(scheduler.scheduled[0].future.cancelled).isFalse()
    }

    @Test
    fun `토큰의 subject 가 호출한 세션의 principal 과 다르면 ACCESS_DENIED 로 거부하고 기존 예약을 건드리지 않는다`() {
        registry.registerSession("session-1", AuthRefreshFakeWebSocketSession("session-1"))
        registry.register("session-1", "old-jti", 1L, fixedInstant.plusSeconds(60))
        val otherUsersToken = fakeJwt(subject = "2", jti = "new-jti", expiresAt = fixedInstant.plus(Duration.ofMinutes(5)))
        val decoder = AuthRefreshFakeJwtDecoder(mapOf("other-users-token" to otherUsersToken))
        val controller = StompAuthRefreshController(decoder, registry, clock)

        val reply = controller.refresh(
            RefreshTokenPayload("other-users-token"),
            AuthRefreshFakePrincipal("1"),
            "session-1",
        )

        assertThat(reply.result).isEqualTo("ERROR")
        assertThat(reply.errorCode).isEqualTo(CommonErrorCode.ACCESS_DENIED.code)
        assertThat(scheduler.scheduled).hasSize(1)
        assertThat(scheduler.scheduled[0].future.cancelled).isFalse()
    }
}
