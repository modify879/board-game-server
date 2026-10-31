package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import com.jsm.boardgame.user.application.command.usecase.LoginCommand
import com.jsm.boardgame.user.application.command.usecase.LoginUseCase
import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.LoginFailedException
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 로그인 실패가 짧은 시간 안에 누적되면 계정을 영구히 잠근다: DB(`User.lockedAt`)에 잠금 시각을
 * 기록하고, 설정된 시간(`app.auth.login-failure-window`) 안에 비밀번호 실패가 `app.auth.login-max-failures` 회 쌓이면(카운터는 Redis, [LoginAttemptLimiter])면 잠근다. 관리자의
 * `POST /api/admin/users/{id}/unlock` 로만 풀린다. 잠긴 계정은 로그인·리프레시 모두
 * `ACCOUNT_LOCKED` 로 거부된다(`RefreshTokenService` 도 같은 검사를 한다).
 *
 * 없는 아이디는 세지 않는다 — 잠글 대상(User 행)이 없어서다. 그 대가로 잠금 응답이 계정 존재를
 * 드러내는 것은 받아들인 트레이드오프다(응답 시간으로 아이디 존재가 새는 것과 같은 종류의 판단).
 *
 * 이 서비스는 바깥 트랜잭션을 열지 않는다 — 비밀번호 해싱·Redis 동안 DB 커넥션을 쥐지 않기 위해서다. DB 접근은
 * `users.findByUsername`(읽기)과, 잠글 때만 여는 짧은 트랜잭션(행 락 재조회 → lock → save)뿐이다.
 * 처음 읽은 인스턴스를 저장하지 않는다 — 그사이 커밋된 비밀번호 변경 등을 덮어쓴다. 잠금 트랜잭션은
 * 예외를 던지기 전에 이미 커밋돼 있다 — 잠근 직후 예외가 나가도 잠금은 롤백되지 않는다.
 *
 * 순서와 중간 실패 시 남는 상태(모두 fail-closed):
 * | 단계 | 실패하면 |
 * |---|---|
 * | 1. 사용자 조회·잠금 확인 (DB) | 로그인 안 됨 |
 * | 2. 비밀번호 실패 시 recordFailure (Redis) | 오류 응답, 로그인 안 됨. 카운트가 안 올라가 잠금이 늦어질 뿐이다 |
 * | 3. 임계 도달 시 트랜잭션: 행 락 재조회 → lock → save | 오류 응답, 로그인 안 됨. 카운터는 이미 임계라 다음 실패가 다시 잠근다 |
 * | 4. 성공: reset (Redis) | 오류 응답, 로그인 안 됨 |
 * | 5. 이전 토큰 블랙리스트 → 소켓 종료 (Redis) | 오류 응답, 새 세션 없음. 이전 토큰이 아직 살아 있을 수 있으나 새 권한이 생기지 않았다 |
 * | 6. 토큰 발급 → 세션 시작 (Redis) | 이전 토큰은 이미 무효이고 새 세션은 없다 — 재로그인하면 된다 |
 */
@Service
class LoginService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    private val realtimeConnections: RealtimeConnections,
    private val loginAttemptLimiter: LoginAttemptLimiter,
    // JwtProperties 는 infrastructure 타입이라 application 이 참조할 수 없다. 값만 @Value 로 받는다.
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
    @Value("\${app.auth.login-max-failures}") private val maxFailures: Int,
    @Value("\${app.auth.login-failure-window}") private val failureWindow: Duration,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) : LoginUseCase {

    override fun login(command: LoginCommand): AuthTokens {
        // 형식 오류도 401 로 감춘다 — 400 으로 새면 아이디 형식 적합 여부가 노출된다.
        // 형식 오류는 실패 횟수로도 세지 않는다 — 사용자 입력 실수가 계정을 잠그면 안 된다.
        val (username, rawPassword) = try {
            Username.of(command.username) to RawPassword.of(command.password)
        } catch (e: BusinessException) {
            throw LoginFailedException("로그인 입력 형식이 올바르지 않습니다: username=${command.username}")
        }

        val user = users.findByUsername(username)
            ?: throw LoginFailedException("존재하지 않는 사용자명입니다: username=${command.username}")

        // 존재 확인 다음, 비밀번호 확인보다 먼저 본다 — 잠긴 계정은 올바른 비밀번호를 내도 거부된다.
        if (user.isLocked) {
            throw AccountLockedException("계정이 잠겨 있습니다: username=${username.value}")
        }

        if (!passwordHasher.matches(rawPassword, user.passwordHash)) {
            val reachedThreshold = loginAttemptLimiter.recordFailure(username, maxFailures, failureWindow)
            if (reachedThreshold) {
                val userId = checkNotNull(user.id) { "조회된 User 는 id 가 채워져 있어야 한다" }
                transactions.executeWithoutResult {
                    val current = users.findByIdForUpdate(userId)
                        ?: throw LoginFailedException("잠그려는 사이 사용자가 사라졌습니다: username=${command.username}")
                    current.lock(Instant.now(clock))
                    users.save(current)
                }
                throw AccountLockedException("비밀번호 실패 누적으로 계정이 잠겼습니다: username=${username.value}")
            }
            throw LoginFailedException("비밀번호가 일치하지 않습니다: username=${command.username}")
        }

        loginAttemptLimiter.reset(username)

        val userId = checkNotNull(user.id) { "조회된 User 는 id 가 채워져 있어야 한다" }

        // 단일 기기 정책: 새 로그인은 이전 기기의 액세스 토큰을 즉시 무효화한다.
        val previous = sessions.currentAccessTokenId(userId)
        previous?.let { previousAccessTokenId ->
            sessions.blacklistAccessToken(previousAccessTokenId, Instant.now(clock).plus(accessTokenTtl))
            realtimeConnections.closeAllOf(userId)
        }

        val tokens = tokenIssuer.issue(userId, user.role)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))
        log.info("로그인 성공: userId={}, role={}, replacedPreviousSession={}, reason=NEW_LOGIN", userId.value, user.role, previous != null)
        return AuthTokens(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoginService::class.java)
    }
}
