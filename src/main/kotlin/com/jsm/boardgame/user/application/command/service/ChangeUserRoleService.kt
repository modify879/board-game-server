package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleCommand
import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleUseCase
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.RealtimeConnections
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 트랜잭션을 열지 않는다 — Redis 호출 동안 DB 커넥션을 쥐지 않기 위해서다. DB 접근은 `findById`(읽기)와
 * `save`(saveAndFlush, 반환 시 커밋) 뿐이다.
 *
 * 순서: 조회·역할 검증(메모리에서 changeRole) → **블랙리스트·소켓 종료(Redis) → save(DB)**.
 * 예전에는 save 뒤 Redis 였고, Redis 가 실패하면 @Transactional 이 save 를 롤백해 "아무 일도 없음"으로 수렴했다.
 * 지금은 그 롤백이 없으므로 순서를 뒤집어 같은 안전성(fail-closed)을 얻는다:
 * | 실패 지점 | 남는 상태 |
 * |---|---|
 * | 블랙리스트/소켓 종료 (Redis) | 역할 그대로, 토큰은 일부 무효 — 사용자는 refresh(옛 역할 그대로 유효)로 이어간다 |
 * | save (DB) | 역할 그대로, 토큰은 무효 — 다시 갱신하면 된다. 권한이 남는 상태는 없다 |
 * 반대 순서(save 먼저)로 Redis 가 실패하면 새 역할은 저장됐는데 옛 권한 토큰이 살아남아 위험하다.
 *
 * 폐기 → save(커밋) → **다시 폐기**. 리프레시와의 경합에서 옛 역할 토큰이 살아남지 않는 이유 —
 * RefreshTokenService 는 rotate(새 토큰을 세션에 저장) **뒤에** 역할을 DB 에서 다시 읽어, 발급에 쓴 역할과
 * 다르면 그 토큰을 폐기하고 거부한다. 리프레시의 (옛 역할 읽기·발급 → rotate 저장 → 재확인) 이 save 커밋 시각 T 에 대해:
 *  - 재확인이 T 이후: 역할 변경을 보고 새 토큰을 스스로 폐기한다.
 *  - 재확인이 T 이전: rotate 도 T 이전이라 새 토큰은 이미 세션의 현재 토큰이고, 두 번째 폐기가 그것을 찾아 죽인다.
 * 어떤 끼어들기 순서에서도 옛 역할 토큰은 살지 못한다. 대가는 경합 시 사용자가 재로그인/재갱신해야 한다는 것뿐이다.
 */
@Service
class ChangeUserRoleService(
    private val users: UserRepository,
    private val sessions: AuthSessionStore,
    private val realtimeConnections: RealtimeConnections,
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
    private val clock: Clock,
) : ChangeUserRoleUseCase {

    override fun changeRole(command: ChangeUserRoleCommand) {
        val role = UserRole.of(command.role)
        val targetUserId = UserId(command.targetUserId)
        val user = users.findById(targetUserId)
            ?: throw UserNotFoundException("역할을 변경하려는 사용자를 찾을 수 없음: userId=${command.targetUserId}")
        user.changeRole(role) // 메모리에서만 — 도메인 검증이 Redis 를 건드리기 전에 끝나게 한다.

        // 블랙리스트를 save() 앞에 둔다(위 KDoc). save 가 실패해도 토큰만 끊긴 안전한 쪽이다.
        //
        // 첫 폐기만으로는 경합이 남는다: 폐기 이후 save 커밋 이전에 들어온 refresh 는 옛 역할을 읽어 옛 역할 토큰을
        // 발급받고, 그 jti 는 방금 폐기한 jti 가 아니라 살아남는다. 그래서 save 뒤에 한 번 더 폐기한다(아래).
        revokeCurrentSession(targetUserId, role)

        users.save(user)

        // 두 번째 폐기 — 위 경합을 닫는 절반이다(나머지 절반은 RefreshTokenService 의 회전 뒤 역할 재확인).
        // 경합 창에서 옛 역할로 발급된 토큰은 rotate 로 세션에 저장되므로 지금의 "현재 토큰"이다.
        // 커밋 뒤에 한 번 더 현재 토큰을 폐기하면 그 토큰이 죽는다. 실패하면 예외(500)가 나가고 저장된 역할은
        // 유지된다. 재시도하면 같은 결과로 수렴한다(changeRole·save·폐기 모두 멱등).
        try {
            revokeCurrentSession(targetUserId, role)
        } catch (e: RuntimeException) {
            log.error("역할 저장 뒤 세션 재폐기에 실패했습니다 — 재시도 필요: userId={}, role={}", command.targetUserId, role, e)
            throw e
        }

        // 리프레시 토큰 세션은 여기서 끊지 않는다(sessions.clear() 호출 안 함). 클라이언트가
        // 이미 타는 401 → refresh 경로가 새 역할이 박힌 액세스 토큰을 넘겨주도록 하는 것이
        // 의도다 — clear() 로 끊으면 재로그인을 강요하게 된다.
    }

    private fun revokeCurrentSession(userId: UserId, role: UserRole) {
        sessions.currentAccessTokenId(userId)?.let {
            sessions.blacklistAccessToken(it, Instant.now(clock).plus(accessTokenTtl))
            realtimeConnections.closeAllOf(userId)
            log.info("역할 변경으로 세션을 폐기했습니다: userId={}, role={}, reason=ROLE_CHANGED", userId.value, role)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChangeUserRoleService::class.java)
    }
}
