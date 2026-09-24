package com.jsm.boardgame.user.infrastructure.persistence.adapter

import com.jsm.boardgame.user.infrastructure.persistence.entity.UserJpaEntity
import com.jsm.boardgame.user.infrastructure.persistence.entity.UserJpaRepository
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.DuplicateUsernameException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `UserRepositoryAdapter.save()` 가 DB unique 제약 위반(DataIntegrityViolationException)을
 * 도메인 예외로 변환하는 경로를 검증한다.
 *
 * `SignUpService` 를 거치지 않고 `UserRepository` 를 직접 호출해 사전 체크를 우회한다 —
 * 그래야 실제 DB 제약 위반이 발생해 변환 코드가 실행된다.
 *
 * `@Transactional` 을 일부러 두지 않는다: 트랜잭션 안에 있으면 제약 위반이 flush/커밋
 * 시점까지 미뤄질 수 있어 "save 호출 시점에 예외가 던져진다"는 가정이 깨질 수 있다.
 * 대신 각 테스트가 고유한 username/nickname 을 써서 테스트 간 격리를 확보한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UserRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var jpaUsers: UserJpaRepository

    private fun uniqueUsername(): String =
        "u" + UUID.randomUUID().toString().replace("-", "").take(9).lowercase()

    private fun uniqueNickname(): String =
        "n" + UUID.randomUUID().toString().replace("-", "").take(5).lowercase()

    private fun newUser(username: String, nickname: String): User =
        User.register(
            username = Username.of(username),
            passwordHash = PasswordHash("hashed-password-value"),
            nickname = Nickname.of(nickname),
        )

    @Test
    fun `같은 username 으로 save 를 두 번 하면 DuplicateUsernameException 이 발생한다`() {
        val username = uniqueUsername()
        users.save(newUser(username = username, nickname = uniqueNickname()))

        val e = assertFailsWith<DuplicateUsernameException> {
            users.save(newUser(username = username, nickname = uniqueNickname()))
        }
        assertEquals(UserErrorCode.DUPLICATE_USERNAME, e.errorCode)
    }

    @Test
    fun `같은 nickname 으로 save 를 두 번 하면 DuplicateNicknameException 이 발생한다`() {
        val nickname = uniqueNickname()
        users.save(newUser(username = uniqueUsername(), nickname = nickname))

        val e = assertFailsWith<DuplicateNicknameException> {
            users.save(newUser(username = uniqueUsername(), nickname = nickname))
        }
        assertEquals(UserErrorCode.DUPLICATE_NICKNAME, e.errorCode)
    }

    @Test
    fun `현재 닉네임 규칙을 위반하는 값도 findByUsername 으로 예외 없이 복원된다`() {
        // Nickname.of() 는 2~12자만 허용하지만, 상한이 더 넓던 과거에 가입해 저장된 닉네임은
        // 이 규칙을 위반할 수 있다. UserJpaRepository 를 직접 써서 도메인 검증(Nickname.of)을
        // 우회하고 그런 상황을 흉내낸다.
        val invalidNickname = "가".repeat(13)
        val username = uniqueUsername()
        jpaUsers.save(
            UserJpaEntity(
                username = username,
                passwordHash = "hashed-password-value",
                nickname = invalidNickname,
                profileImageKey = null,
                role = "USER",
                lockedAt = null,
            ),
        )

        val found = users.findByUsername(Username.of(username))

        assertEquals(invalidNickname, found?.nickname?.value)
    }
}
