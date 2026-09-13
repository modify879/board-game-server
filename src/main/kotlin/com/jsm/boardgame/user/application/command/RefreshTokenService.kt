package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.domain.exception.InvalidRefreshTokenException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

// DB 에 접근하지 않는다(토큰 검증 + Redis 세션만 다룬다) — 트랜잭션이 불필요하다.
@Service
class RefreshTokenService(
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    // JwtProperties(infrastructure/security)는 인프라 계층 타입이라 application 이 참조할 수 없다.
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
) : RefreshTokenUseCase {

    override fun refresh(command: RefreshTokenCommand): IssuedTokens {
        val userId = tokenIssuer.userIdFromRefreshToken(command.refreshToken)
            ?: throw InvalidRefreshTokenException("리프레시 토큰 서명 검증 실패 또는 만료")

        if (!sessions.matchesRefreshToken(userId, command.refreshToken)) {
            // 서명은 유효하지만(회전 이전 토큰) 현재 세션과 다르다 = 탈취된 옛 토큰의 재사용 시도.
            // 세션 전체를 폐기해 정상 사용자도 재로그인하게 만든다 — 공격자에게는 응답을 구분해 주지 않는다.
            sessions.clear(userId)
            throw InvalidRefreshTokenException("리프레시 토큰 재사용 탐지: userId=$userId — 세션 전체 폐기")
        }

        // 회전 시 아직 살아 있는 액세스 토큰도 함께 끊는다.
        sessions.currentAccessTokenId(userId)?.let { currentAccessTokenId ->
            sessions.blacklistAccessToken(currentAccessTokenId, Instant.now().plus(accessTokenTtl))
        }

        val tokens = tokenIssuer.issue(userId)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))
        return tokens
    }
}
