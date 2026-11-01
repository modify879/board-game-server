package com.jsm.boardgame.common.web.stomp

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.common.error.CommonErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.convert.converter.Converter
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private class AuthInterceptorFakeScheduledFuture : ScheduledFuture<Any> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = false
    override fun get(): Any = throw UnsupportedOperationException()
    override fun get(timeout: Long, unit: TimeUnit): Any = throw UnsupportedOperationException()
    override fun getDelay(unit: TimeUnit): Long = throw UnsupportedOperationException()
    override fun compareTo(other: Delayed): Int = throw UnsupportedOperationException()
}

/** register 가 예약하는 횟수 = 등록된 세션 수. */
private class AuthInterceptorFakeTaskScheduler : TaskScheduler {
    val scheduledTimes = mutableListOf<Instant>()

    override fun schedule(task: Runnable, startTime: Instant): ScheduledFuture<*> {
        scheduledTimes += startTime
        return AuthInterceptorFakeScheduledFuture()
    }

    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleAtFixedRate(task: Runnable, startTime: Instant, period: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()
    override fun scheduleAtFixedRate(task: Runnable, period: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
    override fun scheduleWithFixedDelay(task: Runnable, startTime: Instant, delay: Duration): ScheduledFuture<*> =
        throw UnsupportedOperationException()
    override fun scheduleWithFixedDelay(task: Runnable, delay: Duration): ScheduledFuture<*> = throw UnsupportedOperationException()
}

class StompAuthenticationInterceptorTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scheduler = AuthInterceptorFakeTaskScheduler()
    private val registry = StompSessionRegistry(clock, scheduler)
    private val channel = MessageChannel { _, _ -> true }
    private val converter = Converter<Jwt, AbstractAuthenticationToken> { JwtAuthenticationToken(it) }

    private fun jwt(subject: String? = "7", jti: String? = "jti-1", exp: Instant? = now.plusSeconds(300)): Jwt =
        Jwt.withTokenValue("tok").header("alg", "none").claim("k", "v").apply {
            subject?.let { subject(it) }
            jti?.let { jti(it) }
            exp?.let { expiresAt(it) }
        }.build()

    private fun interceptor(decode: (String) -> Jwt = { jwt() }) =
        StompAuthenticationInterceptor(JwtDecoder { decode(it) }, converter, registry)

    private fun connect(
        authorization: String? = "Bearer tok",
        sessionId: String? = "s-1",
        command: StompCommand = StompCommand.CONNECT,
    ): Pair<Message<ByteArray>, StompHeaderAccessor> {
        val accessor = StompHeaderAccessor.create(command)
        sessionId?.let { accessor.sessionId = it }
        authorization?.let { accessor.addNativeHeader("Authorization", it) }
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders) to accessor
    }

    private fun assertAuthRequired(message: Message<*>, interceptor: StompAuthenticationInterceptor = interceptor()) {
        assertThatThrownBy { interceptor.preSend(message, channel) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED)
            }
    }

    @Test
    fun `CONNECT 가 아닌 프레임은 검증 없이 그대로 통과시킨다`() {
        val (message, _) = connect(authorization = null, command = StompCommand.SEND)

        assertThat(interceptor { throw JwtException("unused") }.preSend(message, channel)).isSameAs(message)
        assertThat(scheduler.scheduledTimes).isEmpty()
    }

    @Test
    fun `Authorization 헤더가 없으면 AUTHENTICATION_REQUIRED 로 거부한다`() {
        assertAuthRequired(connect(authorization = null).first)
    }

    @Test
    fun `Authorization 헤더가 Bearer 접두사로 시작하지 않으면 AUTHENTICATION_REQUIRED 로 거부한다`() {
        assertAuthRequired(connect(authorization = "Basic abc").first)
    }

    @Test
    fun `디코더가 JwtException 을 던지면 AUTHENTICATION_REQUIRED 로 거부한다`() {
        assertAuthRequired(connect().first, interceptor { throw JwtException("bad") })
    }

    @Test
    fun `세션 id 가 없으면 AUTHENTICATION_REQUIRED 로 거부하고 세션을 등록하지 않는다`() {
        assertAuthRequired(connect(sessionId = null).first)
        assertThat(scheduler.scheduledTimes).isEmpty()
    }

    @Test
    fun `토큰에 jti 가 없으면 AUTHENTICATION_REQUIRED 로 거부하고 세션을 등록하지 않는다`() {
        assertAuthRequired(connect().first, interceptor { jwt(jti = null) })
        assertThat(scheduler.scheduledTimes).isEmpty()
    }

    @Test
    fun `토큰에 exp 가 없으면 AUTHENTICATION_REQUIRED 로 거부하고 세션을 등록하지 않는다`() {
        assertAuthRequired(connect().first, interceptor { jwt(exp = null) })
        assertThat(scheduler.scheduledTimes).isEmpty()
    }

    @Test
    fun `subject 가 숫자가 아니면 AUTHENTICATION_REQUIRED 로 거부하고 세션을 등록하지 않는다`() {
        assertAuthRequired(connect().first, interceptor { jwt(subject = "abc") })
        assertThat(scheduler.scheduledTimes).isEmpty()
    }

    @Test
    fun `유효한 토큰이면 principal 을 세팅하고 만료 시각에 닫도록 세션을 등록한다`() {
        val (message, accessor) = connect()

        val result = interceptor().preSend(message, channel)

        assertThat(result).isSameAs(message)
        assertThat(accessor.user?.name).isEqualTo("7")
        assertThat(MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor::class.java)).isSameAs(accessor)
        assertThat(scheduler.scheduledTimes).containsExactly(now.plusSeconds(300))
    }
}
