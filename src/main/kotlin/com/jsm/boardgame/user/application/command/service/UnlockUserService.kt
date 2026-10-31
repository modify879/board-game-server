package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.UnlockUserCommand
import com.jsm.boardgame.user.application.command.usecase.UnlockUserUseCase
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.stereotype.Service

/**
 * 트랜잭션을 열지 않는다 — `save` 가 자체로 짧게 커밋하고 Redis 는 그 뒤에 부른다(DB 커넥션을 쥔 채 외부 I/O 금지).
 * 순서: save(DB) → reset(Redis). Redis 가 실패하면 잠금은 풀렸지만 실패 카운터가 남아 재잠금이 빨라질 뿐이다
 * (예외는 500 으로 나가고 재시도하면 같은 결과로 수렴한다). 잠금이 남는 쪽의 실패는 없다.
 */
@Service
class UnlockUserService(
    private val users: UserRepository,
    private val loginAttemptLimiter: LoginAttemptLimiter,
) : UnlockUserUseCase {

    override fun unlock(command: UnlockUserCommand) {
        val user = users.findById(UserId(command.targetUserId))
            ?: throw UserNotFoundException("잠금을 해제하려는 사용자를 찾을 수 없음: userId=${command.targetUserId}")
        user.unlock()
        users.save(user)
        // 실패 카운터도 지운다 — 남아 있으면 해제 직후 몇 번만 더 틀려도 곧바로 재잠금된다.
        loginAttemptLimiter.reset(user.username)
    }
}
