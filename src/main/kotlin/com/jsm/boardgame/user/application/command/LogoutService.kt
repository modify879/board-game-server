package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.AuthSessionStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

// DB 에 접근하지 않는다(Redis 세션만 다룬다) — 트랜잭션이 불필요하다.
@Service
class LogoutService(
    private val sessions: AuthSessionStore,
    // JwtProperties(infrastructure/security)는 인프라 계층 타입이라 application 이 참조할 수 없다.
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
) : LogoutUseCase {

    override fun logout(userId: Long) {
        // 세션이 없어도(이미 로그아웃됐어도) 조용히 성공해야 한다.
        sessions.currentAccessTokenId(userId)?.let { accessTokenId ->
            sessions.blacklistAccessToken(accessTokenId, Instant.now().plus(accessTokenTtl))
        }
        sessions.clear(userId)
    }
}
