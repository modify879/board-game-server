package com.jsm.boardgame.user.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.application.query.port.UserQueryRepository
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UserQueryRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var userQuery: UserQueryRepository

    private fun uniqueUsername(): String =
        "u" + UUID.randomUUID().toString().replace("-", "").take(9).lowercase()

    private fun uniqueNickname(): String =
        "n" + UUID.randomUUID().toString().replace("-", "").take(5).lowercase()

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
    fun `없는 사용자 id 는 existsById 가 false 를 돌려준다`() {
        assertFalse(userQuery.existsById(-1))
    }
}
