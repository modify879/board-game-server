package com.jsm.boardgame.user.infrastructure.persistence.adapter

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.user.infrastructure.persistence.entity.UserJpaEntity
import com.jsm.boardgame.user.infrastructure.persistence.entity.UserJpaRepository
import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.DuplicateUsernameException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
@IntegrationTest
class UserRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var jpaUsers: UserJpaRepository

    @Autowired
    private lateinit var tx: TransactionTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun uniqueUsername(): String =
        "u" + UniqueNames.suffix()

    private fun uniqueNickname(): String =
        "n" + UniqueNames.suffix()

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
    fun `알 수 없는 무결성 위반은 도메인 예외로 바꾸지 않고 그대로 던진다`() {
        // text 컬럼은 NUL 문자를 받지 못해 22021 이 나고, 이는 DataIntegrityViolationException 으로 번역된다 — unique 제약이 아니다.
        val bad = User.register(
            username = Username.of(uniqueUsername()),
            passwordHash = PasswordHash("hashed-password-value"),
            nickname = Nickname.reconstitute("a\u0000b"),
        )

        val e = assertFailsWith<org.springframework.dao.DataIntegrityViolationException> { users.save(bad) }

        assertFalse(e is DuplicateNicknameException || e is DuplicateUsernameException)
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
                withdrawnAt = null,
            ),
        )

        val found = users.findByUsername(Username.of(username))

        assertEquals(invalidNickname, found?.nickname?.value)
    }

    @Test
    fun `탈퇴한 사용자는 findById 와 findByUsername 에서 보이지 않고 사용자명은 계속 쓰인 것으로 센다`() {
        val username = uniqueUsername()
        val saved = users.save(newUser(username = username, nickname = uniqueNickname()))
        saved.withdraw(java.time.Instant.now())
        users.save(saved)

        assertNull(users.findById(saved.id!!))
        assertNull(users.findByUsername(Username.of(username)))
        assertTrue(users.existsByUsername(Username.of(username)))
    }

    @Test
    fun `탈퇴하면 닉네임이 반납돼 다른 사용자가 그 닉네임으로 가입할 수 있다`() {
        val nickname = uniqueNickname()
        val saved = users.save(newUser(username = uniqueUsername(), nickname = nickname))
        saved.withdraw(java.time.Instant.now())
        users.save(saved)

        assertFalse(users.existsByNickname(Nickname.of(nickname)))
        users.save(newUser(username = uniqueUsername(), nickname = nickname))
    }

    /**
     * 사용자 행 락이 `for no key update` 인지 실제 Postgres 의 락 충돌로 본다. 지갑 INSERT 의 FK 검사는 부모 행에
     * `for key share` 를 잡는데, 이것은 `for update` 와는 충돌하고 `for no key update` 와는 충돌하지 않는다.
     * 한 트랜잭션이 행 락을 쥔 동안 다른 커넥션에서 (1) 같은 행의 `for no key update nowait` 는 즉시 실패해야 하고 —
     * 락이 정말 잡혔다는 대조군 — (2) 그 사용자의 지갑 INSERT 는 `lock_timeout` 500ms 안에 끝나야 한다.
     * `for update` 였다면 (2) 가 500ms 뒤 락 타임아웃으로 실패한다. 시간 경합이 없다 — 락은 확인 전에 이미 잡혀 있다.
     */
    @Test
    fun `행 락을 쥔 동안에도 그 사용자의 지갑 INSERT 는 막히지 않는다 - for no key update`() {
        val userId = users.save(newUser(username = uniqueUsername(), nickname = uniqueNickname())).id!!
        val pool = Executors.newSingleThreadExecutor()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder = pool.submit {
                tx.executeWithoutResult {
                    checkNotNull(users.findByIdForUpdate(userId))
                    held.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(held.await(10, TimeUnit.SECONDS))

            val notAcquired = assertFailsWith<DataAccessException> {
                tx.executeWithoutResult {
                    jdbc.queryForList("select id from users where id = ? for no key update nowait", userId.value)
                }
            }
            assertEquals("55P03", (notAcquired.mostSpecificCause as SQLException).sqlState) // lock_not_available
            tx.executeWithoutResult {
                jdbc.execute("set local lock_timeout = '500ms'")
                jdbc.update("insert into wallets (user_id, balance) values (?, 0)", userId.value)
            }

            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdown()
        }
    }

    @Test
    fun `findByIdForUpdate 는 트랜잭션 밖에서 부르면 실패한다`() {
        val userId = users.save(newUser(username = uniqueUsername(), nickname = uniqueNickname())).id!!

        assertFailsWith<IllegalTransactionStateException> { users.findByIdForUpdate(userId) }
    }

    @Test
    fun `탈퇴한 사용자는 findByIdForUpdate 에서도 보이지 않는다`() {
        val saved = users.save(newUser(username = uniqueUsername(), nickname = uniqueNickname()))
        saved.withdraw(java.time.Instant.now())
        users.save(saved)

        assertNull(tx.execute { users.findByIdForUpdate(saved.id!!) })
    }
}
