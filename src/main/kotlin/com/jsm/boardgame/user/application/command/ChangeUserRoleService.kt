package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
@Transactional
class ChangeUserRoleService(
    private val users: UserRepository,
    private val sessions: AuthSessionStore,
    @Value("\${app.jwt.access-token-ttl}") private val accessTokenTtl: Duration,
    private val clock: Clock,
) : ChangeUserRoleUseCase {

    override fun changeRole(command: ChangeUserRoleCommand) {
        val user = users.findById(UserId(command.targetUserId))
            ?: throw UserNotFoundException("역할을 변경하려는 사용자를 찾을 수 없음: userId=${command.targetUserId}")
        user.changeRole(command.role)
        users.save(user)

        // 블랙리스트를 save() 뒤에 둔다. 마지막이라 Redis 가 실패하면 @Transactional 이 save() 를
        // 롤백해 "아무 일도 없었음"으로 수렴한다 — 뒤집으면 역할은 그대로인데 토큰만 끊긴 상태가 남는다.
        //
        // 이 순서가 닫지 못하는 경합이 하나 있다: 이 트랜잭션이 **커밋되기 전에** 들어온
        // POST /api/auth/refresh 는 아직 옛 역할을 읽어 옛 역할이 박힌 토큰을 발급받는데,
        // 그 jti 는 아래에서 읽는 currentAccessTokenId 에 아직 반영되지 않아 블랙리스트를 빠져나간다.
        // 강등이 최대 accessTokenTtl 만큼 늦어진다. 두 문장의 순서를 어떻게 바꿔도 닫히지 않는다 —
        // jti 하나가 아니라 "이 시각 이전에 발급된 토큰 전부 무효" 라는 기준이 있어야 닫힌다.
        // 지금 규모에서 그 비용을 지불하지 않는다.
        sessions.currentAccessTokenId(command.targetUserId)?.let {
            sessions.blacklistAccessToken(it, Instant.now(clock).plus(accessTokenTtl))
        }

        // 리프레시 토큰 세션은 여기서 끊지 않는다(sessions.clear() 호출 안 함). 클라이언트가
        // 이미 타는 401 → refresh 경로가 새 역할이 박힌 액세스 토큰을 넘겨주도록 하는 것이
        // 의도다 — clear() 로 끊으면 재로그인을 강요하게 된다.
    }
}
