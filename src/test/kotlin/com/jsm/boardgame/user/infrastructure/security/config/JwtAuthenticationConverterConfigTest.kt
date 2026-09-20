package com.jsm.boardgame.user.infrastructure.security.config

import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JwtAuthenticationConverterConfigTest {

    private val converter = JwtAuthenticationConverterConfig().jwtAuthenticationConverter()

    private fun jwt(role: String?): Jwt {
        val claims = mutableMapOf<String, Any>("sub" to "1")
        if (role != null) claims["role"] = role
        return Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claims { it.putAll(claims) }
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build()
    }

    // Spring Security 가 인증 인자(factor) 추적용으로 FACTOR_BEARER 권한을 자동으로 얹는다 —
    // 이 테스트가 검증하는 대상이 아니므로 ROLE_ 접두 권한만 골라 비교한다.
    private fun authoritiesOf(role: String?): Set<String?> =
        converter.convert(jwt(role)).authorities
            .map { it.authority }
            .filter { it?.startsWith("ROLE_") == true }
            .toSet()

    @Test
    fun `role 클레임이 ADMIN 이면 ROLE_ADMIN 권한이 부여된다`() {
        assertEquals(setOf("ROLE_ADMIN"), authoritiesOf("ADMIN"))
    }

    @Test
    fun `role 클레임이 USER 이면 ROLE_USER 권한이 부여된다`() {
        assertEquals(setOf("ROLE_USER"), authoritiesOf("USER"))
    }

    @Test
    fun `role 클레임이 없으면 ROLE_USER 로 떨어진다`() {
        assertEquals(setOf("ROLE_USER"), authoritiesOf(null))
    }

    @Test
    fun `알 수 없는 role 값이면 ROLE_USER 로 떨어진다`() {
        assertEquals(setOf("ROLE_USER"), authoritiesOf("SUPERADMIN"))
    }
}
