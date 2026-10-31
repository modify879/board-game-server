package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameCommand
import com.jsm.boardgame.user.domain.exception.InvalidNicknameException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class ChangeNicknameFakeUserRepository : UserRepository {
    private val stored = mutableMapOf<Long, User>()
    var saveCount = 0

    fun put(userId: Long, nickname: String) {
        stored[userId] = User.reconstitute(
            id = UserId(userId),
            username = Username.reconstitute("user_$userId"),
            passwordHash = PasswordHash("hashed"),
            nickname = Nickname.reconstitute(nickname),
            profileImageKey = null,
            role = UserRole.USER,
        )
    }

    override fun findByUsername(username: Username): User? = stored.values.find { it.username == username }
    override fun findById(id: UserId): User? = stored[id.value]
    override fun findByIdForUpdate(id: UserId): User? = findById(id)
    override fun existsByUsername(username: Username): Boolean = stored.values.any { it.username == username }
    override fun existsByNickname(nickname: Nickname): Boolean = stored.values.any { it.nickname == nickname }
    override fun save(user: User): User {
        saveCount++
        stored[user.id!!.value] = user
        return user
    }
}

class ChangeNicknameServiceTest {

    private val users = ChangeNicknameFakeUserRepository()
    private val service = ChangeNicknameService(users)

    @Test
    fun `닉네임을 바꾸면 저장된다`() {
        users.put(1L, "옛닉네임")

        service.changeNickname(ChangeNicknameCommand(1L, "새닉네임"))

        assertEquals(Nickname.of("새닉네임"), users.findById(UserId(1L))!!.nickname)
    }

    @Test
    fun `다른 사용자가 쓰는 닉네임이면 DUPLICATE_NICKNAME 이고 바뀌지 않는다`() {
        users.put(1L, "옛닉네임")
        users.put(2L, "남의닉네임")

        val e = assertFailsWith<DuplicateNicknameException> {
            service.changeNickname(ChangeNicknameCommand(1L, "남의닉네임"))
        }

        assertEquals(UserErrorCode.DUPLICATE_NICKNAME, e.errorCode)
        assertEquals(Nickname.of("옛닉네임"), users.findById(UserId(1L))!!.nickname)
    }

    @Test
    fun `현재와 같은 닉네임이면 중복 오류 없이 저장도 하지 않는다`() {
        users.put(1L, "내닉네임")

        service.changeNickname(ChangeNicknameCommand(1L, "내닉네임"))

        assertEquals(0, users.saveCount)
    }

    @Test
    fun `없는 사용자면 USER_NOT_FOUND 다`() {
        val e = assertFailsWith<UserNotFoundException> {
            service.changeNickname(ChangeNicknameCommand(999L, "새닉네임"))
        }

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `형식이 잘못된 닉네임이면 닉네임 검증 오류다`() {
        users.put(1L, "옛닉네임")

        val e = assertFailsWith<InvalidNicknameException> {
            service.changeNickname(ChangeNicknameCommand(1L, "  "))
        }

        assertEquals(UserErrorCode.NICKNAME_BLANK, e.errorCode)
    }
}
