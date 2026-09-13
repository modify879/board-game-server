package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.common.support.BusinessException
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.domain.exception.LoginFailedException
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    // JwtProperties(infrastructure/security)는 인프라 계층 타입이라 application 이 참조할 수 없다
    // (규칙: 의존성 방향에 예외 없음). 값만 필요하므로 @Value 로 직접 받는다.
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
) : LoginUseCase {

    override fun login(command: LoginCommand): IssuedTokens {
        // username/password 의 형식 오류(InvalidUsernameException/InvalidPasswordException)를
        // 그대로 새어나가게 두면 400(INVALID)으로 응답돼 "이 아이디는 형식이 맞다/틀리다"가
        // 노출된다 — 계정 열거로 이어진다. 형식 오류도 로그인 실패와 동일하게 401 로 감춘다.
        val (username, rawPassword) = try {
            Username.of(command.username) to RawPassword.of(command.password)
        } catch (e: BusinessException) {
            throw LoginFailedException("로그인 입력 형식이 올바르지 않습니다: username=${command.username}")
        }

        val user = users.findByUsername(username)
        if (user == null) {
            // 사용자가 없어도 더미 해시와 비교를 실제로 수행해 "비밀번호 틀림" 케이스와 응답
            // 시간을 맞춘다(PasswordHasher.matches KDoc 참고). 호출 결과는 버려도 되지만 호출
            // 자체는 반드시 일어나야 하므로, 컴파일러/JIT 가 "안 쓰는 호출"로 보고 제거하지
            // 못하도록 결과를 실제 조건문에 써서 로그 메시지를 분기한다.
            val dummyMatched = passwordHasher.matches(rawPassword, null)
            throw LoginFailedException(
                if (dummyMatched) {
                    "존재하지 않는 사용자명입니다: username=${command.username} (더미 해시 우연 일치 — 발생할 수 없음)"
                } else {
                    "존재하지 않는 사용자명입니다: username=${command.username}"
                },
            )
        }

        if (!passwordHasher.matches(rawPassword, user.passwordHash)) {
            throw LoginFailedException("비밀번호가 일치하지 않습니다: username=${command.username}")
        }

        val userId = checkNotNull(user.id) { "조회된 User 는 id 가 채워져 있어야 한다" }.value

        // 단일 기기 정책: 새 로그인은 이전 기기의 액세스 토큰을 즉시 무효화한다.
        sessions.currentAccessTokenId(userId)?.let { previousAccessTokenId ->
            sessions.blacklistAccessToken(previousAccessTokenId, Instant.now().plus(accessTokenTtl))
        }

        val tokens = tokenIssuer.issue(userId)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))
        return tokens
    }
}
