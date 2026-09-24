package com.jsm.boardgame.user.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/** 관리자의 계정 잠금 해제 API(`POST /api/admin/users/{id}/unlock`) 통합 테스트. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminUserUnlockIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    private fun uniqueUsername(): String =
        "u" + UUID.randomUUID().toString().replace("-", "").take(9).lowercase()

    private fun uniqueNickname(): String =
        "n" + UUID.randomUUID().toString().replace("-", "").take(5).lowercase()

    private fun signUp(username: String, password: String): ResultActions =
        mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"username":"$username","password":"$password","passwordConfirm":"$password","nickname":"${uniqueNickname()}"}""",
                ),
        )

    private fun idFromLocation(result: ResultActions): Long {
        val location = result.andReturn().response.getHeader("Location") ?: error("Location 헤더가 없다")
        return location.substringAfterLast("/").toLong()
    }

    private fun login(username: String, password: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        )

    private fun accessTokenOf(result: ResultActions): String =
        JsonPath.read<String>(result.andReturn().response.contentAsString, "$.accessToken")

    private fun unlock(targetId: Long, accessToken: String?): ResultActions {
        val requestBuilder = post("/api/admin/users/$targetId/unlock")
        if (accessToken != null) {
            requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }
        return mockMvc.perform(requestBuilder)
    }

    private fun promoteToAdmin(userId: Long) {
        val user = userRepository.findById(UserId(userId)) ?: error("사용자를 찾을 수 없다: $userId")
        user.changeRole(UserRole.ADMIN)
        userRepository.save(user)
    }

    private fun lockUser(userId: Long) {
        val user = userRepository.findById(UserId(userId)) ?: error("사용자를 찾을 수 없다: $userId")
        user.lock(Instant.now())
        userRepository.save(user)
    }

    @Test
    fun `관리자가 잠금을 풀면 그 사용자는 다시 올바른 비밀번호로 로그인할 수 있다`() {
        val password = "password123"
        val usernameAdmin = uniqueUsername()
        val idAdmin = idFromLocation(signUp(usernameAdmin, password).andExpect(status().isCreated))
        promoteToAdmin(idAdmin)
        val accessTokenAdmin = accessTokenOf(login(usernameAdmin, password).andExpect(status().isOk))

        val usernameLocked = uniqueUsername()
        val idLocked = idFromLocation(signUp(usernameLocked, password).andExpect(status().isCreated))
        lockUser(idLocked)

        login(usernameLocked, password)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"))

        unlock(idLocked, accessTokenAdmin)
            .andExpect(status().isNoContent)

        login(usernameLocked, password)
            .andExpect(status().isOk)
    }

    @Test
    fun `관리자가 아니면 잠금 해제는 403 이다`() {
        val password = "password123"
        val usernameA = uniqueUsername()
        val idA = idFromLocation(signUp(usernameA, password).andExpect(status().isCreated))
        val accessTokenA = accessTokenOf(login(usernameA, password).andExpect(status().isOk))

        val usernameB = uniqueUsername()
        val idB = idFromLocation(signUp(usernameB, password).andExpect(status().isCreated))
        lockUser(idB)

        unlock(idB, accessTokenA)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    @Test
    fun `존재하지 않는 사용자를 잠금 해제하면 USER_NOT_FOUND 다`() {
        val password = "password123"
        val usernameAdmin = uniqueUsername()
        val idAdmin = idFromLocation(signUp(usernameAdmin, password).andExpect(status().isCreated))
        promoteToAdmin(idAdmin)
        val accessTokenAdmin = accessTokenOf(login(usernameAdmin, password).andExpect(status().isOk))

        unlock(999_999_999L, accessTokenAdmin)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
    }
}
