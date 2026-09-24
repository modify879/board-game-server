package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.UnlockUserCommand
import com.jsm.boardgame.user.application.command.usecase.UnlockUserUseCase
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
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
