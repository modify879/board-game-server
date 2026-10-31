package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import com.jsm.boardgame.user.application.command.usecase.ChangePasswordCommand
import com.jsm.boardgame.user.application.command.usecase.ChangePasswordUseCase
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.CurrentPasswordMismatchException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 현재 비밀번호를 다시 확인한 뒤 바꾸고, 세션을 새 토큰으로 갈아끼운다.
 *
 * `LoginService` 처럼 트랜잭션을 열지 않는다 — DB 는 `findById`(읽기)와 `save`(saveAndFlush, 반환 시 커밋)뿐이고
 * 나머지는 Redis 라서 DB 커넥션을 쥔 채 Redis 를 기다리지 않게 한다.
 *
 * 현재 비밀번호 틀림은 로그인 실패와 같은 카운터([LoginAttemptLimiter])에 쌓는다 — 로그인 토큰만 훔친 공격자가
 * 이 엔드포인트로 비밀번호를 무제한 추측하지 못하게 한다. 임계에 닿으면 계정을 잠그고 세션을 끝낸다
 * (`LogoutService` 와 같은 순서: 블랙리스트 → 소켓 종료 → clear).
 *
 * 순서와 중간 실패 시 남는 상태(모두 fail-closed):
 * | 단계 | 실패하면 |
 * |---|---|
 * | 1. 조회·잠금 확인·현재 비밀번호 확인 | 아무것도 안 바뀐다 |
 * | 2. 새 해시 save (DB, 단독 커밋) | 이후 단계는 실행되지 않는다. 비밀번호는 이미 바뀌었고 옛 세션은 그대로다 — 같은 요청을 다시 보내면 옛 비밀번호가 틀려 거부되므로 새 비밀번호로 재로그인한다 |
 * | 3. reset → 이전 토큰 블랙리스트 → closeAfterGrace | 오류 응답. 비밀번호는 바뀌었고 옛 토큰이 살아 있을 수 있다 — 새 세션은 만들어지지 않았다 |
 * | 4. 발급 → 세션 시작 | 옛 토큰은 이미 무효이고 새 세션은 없다 — 새 비밀번호로 재로그인한다 |
 */
@Service
class ChangePasswordService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    private val realtimeConnections: RealtimeConnections,
    private val loginAttemptLimiter: LoginAttemptLimiter,
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
    @Value("\${app.auth.login-max-failures}") private val maxFailures: Int,
    @Value("\${app.auth.login-failure-window}") private val failureWindow: Duration,
    private val clock: Clock,
) : ChangePasswordUseCase {

    override fun changePassword(command: ChangePasswordCommand): AuthTokens {
        val newPassword = RawPassword.of(command.newPassword)
        val userId = UserId(command.userId)
        val user = users.findById(userId)
            ?: throw UserNotFoundException("비밀번호를 바꾸려는 사용자를 찾을 수 없음: userId=${command.userId}")
        if (user.isLocked) {
            throw AccountLockedException("계정이 잠겨 있습니다: userId=${command.userId}")
        }

        // 현재 비밀번호의 형식 오류도 불일치로 본다 — 형식 규칙이 응답으로 새지 않게 한다.
        val currentMatches = try {
            passwordHasher.matches(RawPassword.of(command.currentPassword), user.passwordHash)
        } catch (e: BusinessException) {
            false
        }
        if (!currentMatches) {
            if (loginAttemptLimiter.recordFailure(user.username, maxFailures, failureWindow)) {
                user.lock(Instant.now(clock))
                users.save(user)
                endSession(userId)
                throw AccountLockedException("현재 비밀번호 실패 누적으로 계정이 잠겼습니다: userId=${command.userId}")
            }
            throw CurrentPasswordMismatchException("현재 비밀번호가 일치하지 않습니다: userId=${command.userId}")
        }

        user.changePassword(passwordHasher.hash(newPassword))
        users.save(user)
        loginAttemptLimiter.reset(user.username)

        val previous = sessions.currentAccessTokenId(userId)
        previous?.let { previousAccessTokenId ->
            sessions.blacklistAccessToken(previousAccessTokenId, Instant.now(clock).plus(accessTokenTtl))
            // 클라이언트가 이 응답으로 새 토큰을 받아 인밴드로 갱신할 시간을 준다 — 리프레시 회전과 같다.
            realtimeConnections.closeAfterGrace(previousAccessTokenId)
        }

        val tokens = tokenIssuer.issue(userId, user.role)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))
        log.info("비밀번호 변경: userId={}, replacedPreviousSession={}, reason=PASSWORD_CHANGED", userId.value, previous != null)
        return AuthTokens(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
        )
    }

    private fun endSession(userId: UserId) {
        sessions.currentAccessTokenId(userId)?.let { accessTokenId ->
            sessions.blacklistAccessToken(accessTokenId, Instant.now(clock).plus(accessTokenTtl))
            realtimeConnections.closeAllOf(userId)
        }
        sessions.clear(userId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChangePasswordService::class.java)
    }
}
