package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.RemoveProfileImageUseCase
import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// 트랜잭션을 열지 않는다 — DB 저장을 먼저 커밋하고 파일을 지운다. 파일 삭제 실패는 고아 파일일 뿐이다(ChangeProfileImageService 참고).
@Service
class RemoveProfileImageService(
    private val users: UserRepository,
    private val storage: ProfileImageStorage,
) : RemoveProfileImageUseCase {

    override fun remove(userId: Long) {
        val user = users.findById(UserId(userId))
            ?: throw UserNotFoundException("프로필 이미지를 지우려는 사용자를 찾을 수 없음: userId=$userId")
        val previous = user.profileImageKey ?: return
        user.changeProfileImage(null)
        users.save(user)
        runCatching { storage.delete(previous) }
            .onFailure { log.warn("프로필 이미지 삭제 실패: key={}", previous.value, it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RemoveProfileImageService::class.java)
    }
}
