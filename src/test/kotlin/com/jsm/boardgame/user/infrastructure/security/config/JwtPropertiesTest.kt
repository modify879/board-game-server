package com.jsm.boardgame.user.infrastructure.security.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JwtPropertiesTest {

    private fun properties(secret: String, grace: Duration = Duration.ofSeconds(5)) = JwtProperties(
        secret = secret,
        accessTokenTtl = Duration.ofMinutes(30),
        refreshTokenTtl = Duration.ofDays(14),
        refreshReuseGrace = grace,
    )

    @Test
    fun `32바이트 미만 키로 생성하면 명확한 예외가 난다`() {
        val exception = assertFailsWith<IllegalStateException> { properties("too-short") }

        assertTrue(
            exception.message?.contains("32바이트") == true,
            "예외 메시지가 최소 키 길이를 명시해야 한다: ${exception.message}",
        )
    }

    @Test
    fun `빈 시크릿은 APP_JWT_SECRET 을 요구하며 거부된다`() {
        val exception = assertFailsWith<IllegalStateException> { properties("") }

        assertTrue(
            exception.message?.contains("APP_JWT_SECRET") == true,
            "예외 메시지가 APP_JWT_SECRET 를 언급해야 한다: ${exception.message}",
        )
    }

    @Test
    fun `리프레시 재사용 유예가 1ms 미만이면 거부된다`() {
        listOf(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofSeconds(-1)).forEach { grace ->
            val exception = assertFailsWith<IllegalStateException> { properties("x".repeat(32), grace) }

            assertTrue(
                exception.message?.contains("refresh-reuse-grace") == true,
                "예외 메시지가 설정 키를 언급해야 한다: ${exception.message}",
            )
        }
        properties("x".repeat(32), Duration.ofMillis(1))
    }
}
