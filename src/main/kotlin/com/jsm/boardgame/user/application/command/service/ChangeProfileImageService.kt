package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangeProfileImageCommand
import com.jsm.boardgame.user.application.command.usecase.ChangeProfileImageUseCase
import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.application.exception.ProfileImageTooLargeException
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.ProfileImageType
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

private const val MAX_BYTES = 2 * 1024 * 1024

/**
 * 바깥 트랜잭션을 열지 않는다 — 파일 I/O 동안 DB 커넥션을 쥐지 않는다. DB 는 `findById`(없는 사용자면 파일을 쓰기 전에 거절)와
 * 짧은 트랜잭션(행 락 재조회 → 키 교체 → save)뿐이다. 지울 옛 키는 락 뒤에 읽은 것이다 — 동시 변경이 올린 파일을 고아로 만들지 않는다.
 *
 * 순서: 새 파일 저장 → DB 저장 → 옛 파일 삭제. DB 저장이 실패하면 방금 쓴 파일을 지우고 예외를 다시 던진다.
 *
 * ponytail: 파일과 DB 는 원자적이지 않다 — 저장 직후·삭제 직전에 프로세스가 죽으면 고아 파일이 남는다.
 * 문제가 되면 저장소 디렉터리와 `users.profile_image_key` 를 대조해 지우는 정리 작업을 둔다.
 */
@Service
class ChangeProfileImageService(
    private val users: UserRepository,
    private val storage: ProfileImageStorage,
    private val transactions: TransactionOperations,
) : ChangeProfileImageUseCase {

    override fun change(command: ChangeProfileImageCommand): String {
        // 크기를 먼저 본다 — 큰 파일의 앞부분을 형식 검사로 들여다볼 이유가 없다.
        if (command.bytes.size > MAX_BYTES) {
            throw ProfileImageTooLargeException("프로필 이미지가 너무 크다: size=${command.bytes.size}")
        }
        val type = ProfileImageType.detect(command.bytes)
        val userId = UserId(command.userId)
        users.findById(userId)
            ?: throw UserNotFoundException("프로필 이미지를 바꾸려는 사용자를 찾을 수 없음: userId=${command.userId}")

        val key = storage.store(userId, command.bytes, type)
        val previous = try {
            transactions.execute {
                val user = users.findByIdForUpdate(userId)
                    ?: throw UserNotFoundException("프로필 이미지를 바꾸려는 사용자를 찾을 수 없음: userId=${command.userId}")
                user.profileImageKey.also {
                    user.changeProfileImage(key)
                    users.save(user)
                }
            }
        } catch (e: Exception) {
            runCatching { storage.delete(key) }
                .onFailure { log.warn("저장 실패 뒤 새 프로필 이미지 정리 실패: key={}", key.value, it) }
            throw e
        }
        previous?.let { old ->
            runCatching { storage.delete(old) }
                .onFailure { log.warn("옛 프로필 이미지 삭제 실패: key={}", old.value, it) }
        }
        return key.value
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChangeProfileImageService::class.java)
    }
}
