package com.jsm.boardgame.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
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
import java.time.Instant

private class RevalidatorFakeWebSocketSession(private val sessionId: String) : WebSocketSession {
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

private class RevalidatorFakeJwtDecoder(private val invalidToken: String) : JwtDecoder {
    override fun decode(token: String): Jwt {
        if (token == invalidToken) throw JwtException("invalid token")
        val now = Instant.now()
        return Jwt.withTokenValue(token)
            .header("alg", "none")
            .claim("sub", "1")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .build()
    }
}

class StompSessionRevalidatorTest {

    @Test
    fun `무효화된 토큰의 세션만 닫혀 레지스트리에서 지워지고 유효한 세션은 그대로 남는다`() {
        val registry = StompSessionRegistry()
        val invalidSession = RevalidatorFakeWebSocketSession("invalid-session")
        val validSession = RevalidatorFakeWebSocketSession("valid-session")
        registry.registerSession(invalidSession.id, invalidSession)
        registry.registerToken(invalidSession.id, "invalid-token")
        registry.registerSession(validSession.id, validSession)
        registry.registerToken(validSession.id, "valid-token")

        val revalidator = StompSessionRevalidator(
            registry,
            RevalidatorFakeJwtDecoder(invalidToken = "invalid-token"),
            ThreadPoolTaskScheduler(),
        )

        revalidator.sweep()

        assertThat(invalidSession.closeStatus?.code).isEqualTo(CloseStatus.POLICY_VIOLATION.code)
        assertThat(invalidSession.closeStatus?.reason).isEqualTo("AUTHENTICATION_REQUIRED")
        assertThat(registry.sessionFor(invalidSession.id)).isNull()

        assertThat(validSession.closeStatus).isNull()
        assertThat(registry.sessionFor(validSession.id)).isNotNull()
    }
}
