package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.exception.InvalidRefreshTokenException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

// DB 에 접근하지 않는다(토큰 검증 + Redis 세션만 다룬다) — 트랜잭션이 불필요하다.
@Service
class RefreshTokenService(
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    // JwtProperties(infrastructure/security)는 인프라 계층 타입이라 application 이 참조할 수 없다.
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
    // Instant.now() 를 직접 부르지 않고 주입받는다 — 테스트가 시간을 제어할 수 있어야 하기 때문이다.
    private val clock: Clock,
) : RefreshTokenUseCase {

    override fun refresh(command: RefreshTokenCommand): IssuedTokens {
        val userId = tokenIssuer.userIdFromRefreshToken(command.refreshToken)
            ?: throw InvalidRefreshTokenException("리프레시 토큰 서명 검증 실패 또는 만료")

        // rotate() 로 확인과 교체를 한 번에 묶기 위해, 검사보다 먼저 새 토큰을 발급해야 한다.
        // rotate() 가 Mismatch 를 돌려주면 이 토큰은 어디에도 쓰이지 않고 그냥 버려진다 —
        // 세션에도, 블랙리스트에도 아직 아무것도 쓰지 않았으므로 상태를 남기지 않아 안전하다.
        // "검사 후 발급"으로 순서를 바꾸면 검사와 발급 사이에 경합 창이 다시 생겨, 이번에 고치려는
        // "확인 후 실행"의 비원자성 문제가 그대로 재현된다.
        val tokens = tokenIssuer.issue(userId)

        // 직전 토큰 유예 칸에 무엇이 들어가는지는 여기서 정하지 않는다 — sessions.rotate() 구현이
        // 이번 회전으로 밀려난 현재 토큰을 스스로 그 칸에 채운다. AuthSessionStore.rotate 문서 참고.
        val result = sessions.rotate(
            userId,
            command.refreshToken,
            AuthSession(
                accessTokenId = tokens.accessTokenId,
                refreshToken = tokens.refreshToken,
                refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
            ),
        )

        return when (result) {
            is RotationResult.Rotated -> {
                // 회전 시 아직 살아 있는 액세스 토큰도 함께 끊는다.
                result.previousAccessTokenId?.let { previousAccessTokenId ->
                    sessions.blacklistAccessToken(previousAccessTokenId, Instant.now(clock).plus(accessTokenTtl))
                }
                tokens
            }

            RotationResult.Mismatch -> {
                // 서명은 유효하지만(회전 이전 토큰) 현재 세션과 다르다 = 탈취된 옛 토큰의 재사용 시도.
                // 세션 전체를 폐기해 정상 사용자도 재로그인하게 만든다 — 공격자에게는 응답을 구분해 주지 않는다.
                //
                // 반드시 clear() 보다 먼저 현재 액세스 토큰을 블랙리스트에 넣어야 한다. clear() 가
                // 세션 레코드를 지우면 currentAccessTokenId(userId) 가 null 이 되어, 그 시점까지 살아
                // 있던(탈취됐을 수 있는) 액세스 토큰을 더 이상 블랙리스트에 넣을 방법이 없어진다 —
                // 그러면 정상 사용자만 로그아웃되고 공격자의 액세스 토큰은 accessTokenTtl 만료까지
                // 계속 통하게 된다. 이 순서를 뒤집으면 그 버그가 그대로 재현된다. LogoutService 와
                // 동일한 순서다.
                sessions.currentAccessTokenId(userId)?.let { currentAccessTokenId ->
                    sessions.blacklistAccessToken(currentAccessTokenId, Instant.now(clock).plus(accessTokenTtl))
                }
                sessions.clear(userId)
                throw InvalidRefreshTokenException("리프레시 토큰 재사용 탐지: userId=$userId — 세션 전체 폐기")
            }
        }
    }
}
