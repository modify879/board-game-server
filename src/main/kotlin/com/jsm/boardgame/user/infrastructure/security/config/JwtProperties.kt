package com.jsm.boardgame.user.infrastructure.security.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * HS256 은 대칭키의 강도가 곧 서명 안전성이므로 최소 256비트(32바이트)를 강제한다.
 * RFC 7518 §3.2 요구사항이며, 짧은 키를 그냥 통과시키면 운영에서 서명이 쉽게 위조된다.
 */
private const val MIN_SECRET_BYTES = 32

@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    val secret: String,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
    /** 응답 유실로 인한 재시도를 탈취와 구분하기 위한 유예. 직전 토큰 한 세대에만 적용된다. */
    val refreshReuseGrace: Duration,
) {
    init {
        check(secret.isNotBlank()) {
            "app.jwt.secret 이 비어 있습니다. APP_JWT_SECRET 환경변수를 설정하세요."
        }
        val bytes = secret.toByteArray(Charsets.UTF_8)
        check(bytes.size >= MIN_SECRET_BYTES) {
            "app.jwt.secret 은 HS256 서명에 최소 ${MIN_SECRET_BYTES}바이트가 필요합니다 " +
                "(현재 ${bytes.size}바이트). APP_JWT_SECRET 환경변수를 더 긴 값으로 설정하세요."
        }
        // Redis rotate Lua 의 SET ... PX 가 0 이하를 거부한다 — Lua 는 롤백이 없어 앞선 쓰기가 남은 채 500 이 된다.
        check(refreshReuseGrace >= Duration.ofMillis(1)) {
            "app.jwt.refresh-reuse-grace 는 1ms 이상이어야 합니다 (현재 $refreshReuseGrace)."
        }
    }
}
