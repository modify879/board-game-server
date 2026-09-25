package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import com.jsm.boardgame.user.application.command.usecase.LoginCommand
import com.jsm.boardgame.user.application.command.usecase.LoginUseCase
import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.LoginFailedException
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

/**
 * 로그인 실패가 짧은 시간 안에 누적되면 계정을 영구히 잠근다: DB(`User.lockedAt`)에 잠금 시각을
 * 기록하고, 15분 안에 비밀번호 5회 실패(카운터는 Redis, [LoginAttemptLimiter])면 잠근다. 관리자의
 * `POST /api/admin/users/{id}/unlock` 로만 풀린다. 잠긴 계정은 로그인·리프레시 모두
 * `ACCOUNT_LOCKED` 로 거부된다(`RefreshTokenService` 도 같은 검사를 한다).
 *
 * 없는 아이디는 세지 않는다 — 잠글 대상(User 행)이 없어서다. 그 대가로 잠금 응답이 계정 존재를
 * 드러내는 것은 받아들인 트레이드오프다(응답 시간으로 아이디 존재가 새는 것과 같은 종류의 판단).
 *
 * readOnly 가 아니다 — 잠글 때 `users.save(user)` 로 실제 커밋해야 한다(UserRepositoryAdapter.save
 * 는 saveAndFlush 라 UPDATE 자체는 즉시 나가지만, 트랜잭션이 롤백되면 그 변경은 커밋되지 않는다).
 */
@Service
// 잠근 직후 예외를 던지므로 기본 롤백이면 잠금이 사라진다 — 그래서 이 두 예외는 롤백하지 않는다.
@Transactional(noRollbackFor = [LoginFailedException::class, AccountLockedException::class])
class LoginService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    private val loginAttemptLimiter: LoginAttemptLimiter,
    // JwtProperties 는 infrastructure 타입이라 application 이 참조할 수 없다. 값만 @Value 로 받는다.
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
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
            val reachedThreshold = loginAttemptLimiter.recordFailure(username, MAX_FAILURES, FAILURE_WINDOW)
            if (reachedThreshold) {
                user.lock(Instant.now(clock))
                users.save(user)
                throw AccountLockedException("비밀번호 실패 누적으로 계정이 잠겼습니다: username=${username.value}")
            }
            throw LoginFailedException("비밀번호가 일치하지 않습니다: username=${command.username}")
        }

        loginAttemptLimiter.reset(username)

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

    companion object {
        const val MAX_FAILURES = 5
        val FAILURE_WINDOW: Duration = Duration.ofMinutes(15)
    }
}
