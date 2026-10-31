package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameCommand
import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameUseCase
import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class ChangeNicknameService(
    private val users: UserRepository,
) : ChangeNicknameUseCase {

    override fun changeNickname(command: ChangeNicknameCommand) {
        val nickname = Nickname.of(command.nickname)
        val user = users.findById(UserId(command.userId))
            ?: throw UserNotFoundException("닉네임을 바꾸려는 사용자를 찾을 수 없음: userId=${command.userId}")
        if (user.nickname == nickname) return

        // 사전 체크는 친절한 오류 응답용이다. 동시 요청의 유일성은 DB unique 제약이 보장한다.
        if (users.existsByNickname(nickname)) {
            throw DuplicateNicknameException("이미 사용 중인 닉네임입니다: nickname=${command.nickname}")
        }
        user.changeNickname(nickname)
        users.save(user)
    }
}
