package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.DuplicateUsernameException
import com.jsm.boardgame.user.domain.exception.InvalidPasswordException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeUserRepository : UserRepository {
    val stored = mutableListOf<User>()
    private var sequence = 0L

    override fun findByUsername(username: Username): User? = stored.find { it.username == username }

    override fun existsByUsername(username: Username): Boolean =
        stored.any { it.username == username }

    override fun existsByNickname(nickname: Nickname): Boolean =
        stored.any { it.nickname == nickname }

    override fun save(user: User): User {
        sequence += 1
        val saved = User.reconstitute(
            id = UserId(sequence),
            username = user.username,
            passwordHash = user.passwordHash,
            nickname = user.nickname,
            profileImageKey = user.profileImageKey,
        )
        stored += saved
        return saved
    }
}

private class FixedPasswordHasher : PasswordHasher {
    override fun hash(raw: RawPassword): PasswordHash = PasswordHash("hashed:${raw.value}")
    override fun matches(raw: RawPassword, hash: PasswordHash): Boolean = hash.value == "hashed:${raw.value}"
}

class SignUpServiceTest {

    private val users = FakeUserRepository()
    private val service = SignUpService(users, FixedPasswordHasher())

    @Test
    fun `정상 가입은 0 이 아닌 식별자를 돌려준다`() {
        val id = service.signUp(SignUpCommand(username = "user_01", password = "password1", nickname = "길동이"))
        assertTrue(id != 0L)
    }

    @Test
    fun `가입한 사용자의 프로필 이미지는 null 이다`() {
        service.signUp(SignUpCommand(username = "user_01", password = "password1", nickname = "길동이"))

        val saved = users.stored.single()
        assertNull(saved.profileImageKey)
    }

    @Test
    fun `저장된 비밀번호는 평문이 아니라 해셔를 거친 값이다`() {
        service.signUp(SignUpCommand(username = "user_01", password = "password1", nickname = "길동이"))

        val saved = users.stored.single()
        assertNotEquals("password1", saved.passwordHash.value)
        assertEquals("hashed:password1", saved.passwordHash.value)
    }

    @Test
    fun `아이디가 중복되면 DuplicateUsernameException 이 발생한다`() {
        service.signUp(SignUpCommand(username = "user_01", password = "password1", nickname = "길동이"))

        val e = assertFailsWith<DuplicateUsernameException> {
            service.signUp(SignUpCommand(username = "user_01", password = "password2", nickname = "철수다"))
        }
        assertEquals(UserErrorCode.DUPLICATE_USERNAME, e.errorCode)
    }

    @Test
    fun `닉네임이 중복되면 DuplicateNicknameException 이 발생한다`() {
        service.signUp(SignUpCommand(username = "user_01", password = "password1", nickname = "길동이"))

        val e = assertFailsWith<DuplicateNicknameException> {
            service.signUp(SignUpCommand(username = "user_02", password = "password2", nickname = "길동이"))
        }
        assertEquals(UserErrorCode.DUPLICATE_NICKNAME, e.errorCode)
    }

    @Test
    fun `짧은 비밀번호는 저장 전에 거부된다`() {
        val e = assertFailsWith<InvalidPasswordException> {
            service.signUp(SignUpCommand(username = "user_01", password = "short1", nickname = "길동이"))
        }
        assertEquals(UserErrorCode.PASSWORD_TOO_SHORT, e.errorCode)
        assertTrue(users.stored.isEmpty())
    }
}
