package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenCommand
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenUseCase
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.InvalidRefreshTokenException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

// 새 액세스 토큰에는 현재 역할을 박아야 강등이 갱신 한 번으로 반영된다 — 그래서 users.findById 로
// DB 를 읽는다. 역할을 Redis 세션 문자열에 끼워 넣지 않는 이유: 직렬화 포맷과 rotate() 의 Lua
// 스크립트를 함께 건드려야 하고, 이 저장소에서 그 자리가 이미 두 번 버그가 났다.
@Service
@Transactional(readOnly = true)
class RefreshTokenService(
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    private val users: UserRepository,
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
    private val clock: Clock,
) : RefreshTokenUseCase {

    override fun refresh(command: RefreshTokenCommand): AuthTokens {
        val userId = sessions.userIdForRefreshToken(command.refreshToken)
            ?: throw InvalidRefreshTokenException("알 수 없거나 만료된 리프레시 토큰")

        // rotate() 로 확인과 교체를 한 번에 묶기 위해, 검사보다 먼저 새 토큰을 발급해야 한다.
        // rotate() 가 Mismatch 를 돌려주면 이 토큰은 어디에도 쓰이지 않고 그냥 버려진다 —
        // 세션에도, 블랙리스트에도 아직 아무것도 쓰지 않았으므로 상태를 남기지 않아 안전하다.
        // "검사 후 발급"으로 순서를 바꾸면 검사와 발급 사이에 경합 창이 다시 생겨, 이번에 고치려는
        // "확인 후 실행"의 비원자성 문제가 그대로 재현된다.
        val user = users.findById(UserId(userId))
            ?: throw InvalidRefreshTokenException("리프레시 토큰의 사용자가 존재하지 않음: userId=$userId")
        // 이 검사가 없으면 잠긴 계정이 기존 리프레시 토큰으로 14일간 계속 인증된다.
        if (user.isLocked) {
            throw AccountLockedException("잠긴 계정의 리프레시 시도: userId=$userId")
        }
        val tokens = tokenIssuer.issue(userId, user.role)

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
                AuthTokens(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    accessTokenExpiresAt = tokens.accessTokenExpiresAt,
                )
            }

            RotationResult.Mismatch -> {
                // 발급된 적은 있으나 현재 세션과 다르다 = 탈취된 옛 토큰의 재사용 시도.
                // 세션 전체를 폐기해 정상 사용자도 재로그인하게 만든다 — 공격자에게는 응답을 구분해 주지 않는다.
                //
                // 반드시 clear() 보다 먼저 현재 액세스 토큰을 블랙리스트에 넣어야 한다. clear() 가
                // 세션 레코드를 지우면 currentAccessTokenId(userId) 가 null 이 되어, 그 시점까지 살아
                // 있던(탈취됐을 수 있는) 액세스 토큰을 더 이상 블랙리스트에 넣을 방법이 없어진다 —
                // 그러면 정상 사용자만 로그아웃되고 공격자의 액세스 토큰은 accessTokenTtl 만료까지
                // 계속 통하게 된다. 이 순서를 뒤집으면 그 버그가 그대로 재현된다. LogoutService 와
                // 동일한 순서다.
                val currentAccessTokenId = sessions.currentAccessTokenId(userId)
                if (currentAccessTokenId != null) {
                    sessions.blacklistAccessToken(currentAccessTokenId, Instant.now(clock).plus(accessTokenTtl))
                    sessions.clear(userId)
                    throw InvalidRefreshTokenException("리프레시 토큰 재사용 탐지: userId=$userId — 세션 전체 폐기")
                } else {
                    throw InvalidRefreshTokenException("리프레시 토큰에 해당하는 세션 없음: userId=$userId")
                }
            }
        }
    }
}
