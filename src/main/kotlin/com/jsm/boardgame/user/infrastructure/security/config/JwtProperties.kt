package com.jsm.boardgame.user.infrastructure.security.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    val secret: String,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
    /** 응답 유실로 인한 재시도를 탈취와 구분하기 위한 유예. 직전 토큰 한 세대에만 적용된다. */
    val refreshReuseGrace: Duration,
)
