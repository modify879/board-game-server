package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.common.support.BusinessException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.domain.exception.LoginFailedException
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

// UserRepository.findByUsername 으로 DB 를 읽기만 한다.
@Service
@Transactional(readOnly = true)
class LoginService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    // JwtProperties 는 infrastructure 타입이라 application 이 참조할 수 없다. 값만 @Value 로 받는다.
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
    private val clock: Clock,
) : LoginUseCase {

    override fun login(command: LoginCommand): AuthTokens {
        // 형식 오류도 401 로 감춘다 — 400 으로 새면 아이디 형식 적합 여부가 노출된다.
        val (username, rawPassword) = try {
            Username.of(command.username) to RawPassword.of(command.password)
        } catch (e: BusinessException) {
            throw LoginFailedException("로그인 입력 형식이 올바르지 않습니다: username=${command.username}")
        }

        val user = users.findByUsername(username)
            ?: throw LoginFailedException("존재하지 않는 사용자명입니다: username=${command.username}")

        if (!passwordHasher.matches(rawPassword, user.passwordHash)) {
            throw LoginFailedException("비밀번호가 일치하지 않습니다: username=${command.username}")
        }

        val userId = checkNotNull(user.id) { "조회된 User 는 id 가 채워져 있어야 한다" }.value

        // 단일 기기 정책: 새 로그인은 이전 기기의 액세스 토큰을 즉시 무효화한다.
        sessions.currentAccessTokenId(userId)?.let { previousAccessTokenId ->
            sessions.blacklistAccessToken(previousAccessTokenId, Instant.now(clock).plus(accessTokenTtl))
        }

        val tokens = tokenIssuer.issue(userId, user.role)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))
        return AuthTokens(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
        )
    }
}
