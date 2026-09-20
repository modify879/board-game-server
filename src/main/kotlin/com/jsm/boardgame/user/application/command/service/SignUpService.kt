package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.SignUpCommand
import com.jsm.boardgame.user.application.command.usecase.SignUpUseCase
import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.DuplicateUsernameException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class SignUpService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
) : SignUpUseCase {

    override fun signUp(command: SignUpCommand): Long {
        val username = Username.of(command.username)
        val nickname = Nickname.of(command.nickname)
        val rawPassword = RawPassword.of(command.password)

        // 사전 체크는 친절한 오류 응답용이다. 동시 요청의 유일성은 DB unique 제약이 보장한다.
        if (users.existsByUsername(username)) {
            throw DuplicateUsernameException("이미 사용 중인 사용자명입니다: username=${command.username}")
        }
        if (users.existsByNickname(nickname)) {
            throw DuplicateNicknameException("이미 사용 중인 닉네임입니다: nickname=${command.nickname}")
        }

        val passwordHash = passwordHasher.hash(rawPassword)
        val user = User.register(username = username, passwordHash = passwordHash, nickname = nickname)
        val saved = users.save(user)

        val id = checkNotNull(saved.id) { "save 이후에는 User.id 가 채워져 있어야 한다" }
        return id.value
    }
}
