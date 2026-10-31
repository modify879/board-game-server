package com.jsm.boardgame.user.infrastructure.persistence.adapter

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.user.application.query.port.UserQueryRepository
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class UserQueryRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var userQuery: UserQueryRepository

    private fun uniqueUsername(): String =
        "u" + UniqueNames.suffix()

    private fun uniqueNickname(): String =
        "n" + UniqueNames.suffix()

    @Test
    fun `저장된 사용자 id 는 existsById 가 true 를 돌려준다`() {
        val saved = users.save(
            User.register(
                username = Username.of(uniqueUsername()),
                passwordHash = PasswordHash("hashed-password-value"),
                nickname = Nickname.of(uniqueNickname()),
            ),
        )

        assertTrue(userQuery.existsById(saved.id!!.value))
    }

    @Test
    fun `findIdByUsername 은 정확히 일치하는 아이디만 찾고 탈퇴한 사용자는 찾지 않는다`() {
        val username = uniqueUsername()
        val saved = users.save(
            User.register(Username.of(username), PasswordHash("hashed-password-value"), Nickname.of(uniqueNickname())),
        )

        assertEquals(saved.id!!.value, userQuery.findIdByUsername(username))
        assertNull(userQuery.findIdByUsername(username.dropLast(1)))

        saved.withdraw(java.time.Instant.now())
        users.save(saved)
        assertNull(userQuery.findIdByUsername(username))
    }

    @Test
    fun `없는 사용자 id 는 existsById 가 false 를 돌려준다`() {
        assertFalse(userQuery.existsById(-1))
    }

    @Test
    fun `탈퇴한 사용자는 조회 경로의 어디에서도 보이지 않는다`() {
        val nickname = uniqueNickname()
        val saved = users.save(
            User.register(Username.of(uniqueUsername()), PasswordHash("hashed-password-value"), Nickname.of(nickname)),
        )
        val id = saved.id!!.value
        assertTrue(userQuery.findProfileById(id) != null)

        saved.withdraw(java.time.Instant.now())
        users.save(saved)

        assertFalse(userQuery.existsById(id))
        assertNull(userQuery.findProfileById(id))
        assertNull(userQuery.findSummaryById(id))
        assertTrue(userQuery.search("withdrawn-user-$id", org.springframework.data.domain.PageRequest.of(0, 10)).isEmpty)
    }
}
