package com.jsm.boardgame.user.application.command.support

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.user.application.command.usecase.LogoutUseCase
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.CurrentPasswordMismatchException
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 이미 로그인한 사용자가 비밀번호를 다시 입력해 본인임을 확인하는 단계(비밀번호 변경·탈퇴)가 공유한다.
 *
 * 잠긴 계정은 거부하고, 틀리면 로그인 실패와 같은 카운터([LoginAttemptLimiter])에 쌓는다 — 토큰만 훔친 공격자가
 * 이 경로로 비밀번호를 무제한 추측하지 못하게 한다. 임계에 닿으면 계정을 잠그고 세션을 끝낸다([LogoutUseCase]).
 * 트랜잭션을 열지 않는다(`LoginService` 와 같은 이유) — 잠금 `save` 는 반환 시 커밋돼 있다.
 */
@Component
class CurrentPasswordVerifier(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val loginAttemptLimiter: LoginAttemptLimiter,
    private val logout: LogoutUseCase,
    @Value("\${app.auth.login-max-failures}") private val maxFailures: Int,
    @Value("\${app.auth.login-failure-window}") private val failureWindow: Duration,
    private val clock: Clock,
) {

    fun verify(user: User, currentPassword: String) {
        val userId = checkNotNull(user.id) { "조회된 User 는 id 가 채워져 있어야 한다" }
        if (user.isLocked) {
            throw AccountLockedException("계정이 잠겨 있습니다: userId=${userId.value}")
        }

        // 현재 비밀번호의 형식 오류도 불일치로 본다 — 형식 규칙이 응답으로 새지 않게 한다.
        val matches = try {
            passwordHasher.matches(RawPassword.of(currentPassword), user.passwordHash)
        } catch (e: BusinessException) {
            false
        }
        if (matches) return

        if (loginAttemptLimiter.recordFailure(user.username, maxFailures, failureWindow)) {
            user.lock(Instant.now(clock))
            users.save(user)
            logout.logout(userId.value)
            throw AccountLockedException("현재 비밀번호 실패 누적으로 계정이 잠겼습니다: userId=${userId.value}")
        }
        throw CurrentPasswordMismatchException("현재 비밀번호가 일치하지 않습니다: userId=${userId.value}")
    }
}
