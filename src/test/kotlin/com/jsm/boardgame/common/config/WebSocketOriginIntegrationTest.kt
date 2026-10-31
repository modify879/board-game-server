package com.jsm.boardgame.common.config

import com.jsm.boardgame.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.Socket

/** nginx 뒤에서 TLS 가 끝나 Origin 은 https, 서버가 보는 Host 는 http 인 상황을 원시 핸드셰이크로 재현한다. */
@IntegrationTest
class WebSocketOriginIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    // 핸드셰이크 응답의 상태 코드. 인증은 핸드셰이크 이후 STOMP CONNECT 에서 하므로 필요 없다.
    private fun handshakeStatus(host: String, origin: String?): Int =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 5000
            val request = buildString {
                append("GET /ws HTTP/1.1\r\nHost: $host\r\n")
                append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
        }

    @Test
    fun `nginx 뒤의 https Origin 은 핸드셰이크가 통과한다`() {
        assertThat(handshakeStatus("modify95.iptime.org:8444", "https://modify95.iptime.org:8444")).isEqualTo(101)
    }

    @Test
    fun `허용 목록에 없는 Origin 은 403 이다`() {
        assertThat(handshakeStatus("modify95.iptime.org:8444", "https://evil.example.com")).isEqualTo(403)
    }

    @Test
    fun `Origin 헤더가 없으면 통과한다`() {
        assertThat(handshakeStatus("localhost:$port", null)).isEqualTo(101)
    }

    @Test
    fun `same-origin 은 패턴과 무관하게 통과한다`() {
        assertThat(handshakeStatus("localhost:$port", "http://localhost:$port")).isEqualTo(101)
    }
}
