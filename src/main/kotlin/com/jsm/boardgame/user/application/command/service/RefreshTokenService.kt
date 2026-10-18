package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenCommand
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenUseCase
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.application.exception.AccountLockedException
import com.jsm.boardgame.user.application.exception.InvalidRefreshTokenException
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

// 트랜잭션을 열지 않는다 — DB 는 users.findById(자체 읽기 트랜잭션) 한 번뿐이고 나머지는 전부 Redis 라서,
// 바깥 트랜잭션이 있으면 Redis 가 느릴 때 DB 커넥션을 쥔 채 기다리게 된다.
//
// 순서와 중간 실패 시 남는 상태(모두 fail-closed). DB 쓰기가 없으므로 되돌릴 것이 없다:
//  1. 리프레시 토큰 → userId (Redis) / 사용자 조회·잠금 확인 (DB) / 발급 — 실패하면 아무것도 안 바뀐다.
//  2. rotate (Redis, 원자) — 실패하면 옛 세션 그대로. 클라이언트는 옛 토큰으로 다시 시도한다.
//  3. Rotated: 직전 액세스 토큰 블랙리스트 → closeAfterGrace. 블랙리스트가 실패하면 오류 응답을 받지만
//     세션은 이미 회전돼 있어 클라이언트가 가진 옛 리프레시 토큰은 유예 뒤 재사용 탐지로 세션 전체가 폐기된다.
//  4. Mismatch: 블랙리스트 → closeAllOf → clear 순서 고정. 중간에 실패하면 세션이 남지만 이미 블랙리스트에 오른
//     토큰은 무효이고, 다음 시도가 다시 Mismatch 로 같은 폐기를 이어간다.
//
// 역할 재확인: rotate 뒤 DB 를 한 번 더 읽는다(정상 경로의 추가 비용은 PK 조회 1회, 짧은 읽기 트랜잭션).
// 역할 변경과 겹쳤을 때만 REFRESH_TOKEN_INVALID 로 거부된다 — 논증은 ChangeUserRoleService KDoc.
//
// 새 액세스 토큰에는 현재 역할을 박아야 강등이 갱신 한 번으로 반영된다 — 그래서 users.findById 로
// DB 를 읽는다. 역할을 Redis 세션 문자열에 끼워 넣지 않는 이유: 직렬화 포맷과 rotate() 의 Lua
// 스크립트를 함께 건드려야 하고, 이 저장소에서 그 자리가 이미 두 번 버그가 났다.
@Service
class RefreshTokenService(
    private val tokenIssuer: AuthTokenIssuer,
    private val sessions: AuthSessionStore,
    private val realtimeConnections: RealtimeConnections,
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
        // "검사 후 발급"으로 순서를 바꾸면 검사와 발급 사이에 경합 창이 생겨 "확인 후 실행"이 비원자적이 된다.
        val user = users.findById(userId)
            ?: throw InvalidRefreshTokenException("리프레시 토큰의 사용자가 존재하지 않음: userId=${userId.value}")
        // 이 검사가 없으면 잠긴 계정이 기존 리프레시 토큰으로 14일간 계속 인증된다.
        if (user.isLocked) {
            throw AccountLockedException("잠긴 계정의 리프레시 시도: userId=${userId.value}")
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
                result.previousAccessTokenId?.let { previousAccessTokenId ->
                    sessions.blacklistAccessToken(previousAccessTokenId, Instant.now(clock).plus(accessTokenTtl))
                    // 유예를 두고 닫는다 — 클라이언트가 이 REST 응답을 받고 곧바로 인밴드로 새 토큰을
                    // 보낼 시간을 준다(StompRealtimeConnections 의 유예 `app.auth.stomp-rotation-grace` 참고).
                    realtimeConnections.closeAfterGrace(previousAccessTokenId)
                }
                // 회전으로 새 토큰이 세션에 **저장된 뒤** 역할을 다시 읽는다. 발급에 쓴 역할과 다르면 그 사이에
                // 역할이 바뀐 것이다 — 새 토큰을 폐기하고 세션을 정리해 재로그인시킨다. 이 재확인이 저장 뒤여야
                // ChangeUserRoleService 의 "save 뒤 재폐기"와 합쳐 모든 끼어들기 순서가 닫힌다(그쪽 KDoc 참고).
                if (users.findById(userId)?.role != user.role) {
                    sessions.blacklistAccessToken(tokens.accessTokenId, Instant.now(clock).plus(accessTokenTtl))
                    sessions.clear(userId)
                    throw InvalidRefreshTokenException("리프레시 중 역할 변경 감지: userId=${userId.value} — 새 토큰 폐기")
                }
                AuthTokens(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    accessTokenExpiresAt = tokens.accessTokenExpiresAt,
                    refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
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
                    realtimeConnections.closeAllOf(userId)
                    sessions.clear(userId)
                    throw InvalidRefreshTokenException("리프레시 토큰 재사용 탐지: userId=${userId.value} — 세션 전체 폐기")
                } else {
                    throw InvalidRefreshTokenException("리프레시 토큰에 해당하는 세션 없음: userId=${userId.value}")
                }
            }
        }
    }
}
