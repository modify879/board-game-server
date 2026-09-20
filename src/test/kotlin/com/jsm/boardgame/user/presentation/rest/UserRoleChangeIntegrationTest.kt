package com.jsm.boardgame.user.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
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
import java.util.Base64
import java.util.UUID

/**
 * 관리자가 다른 사용자의 역할을 바꾸는 흐름 전체를 하나로 잇는다(CLAUDE.md 가 경고하는
 * "성격이 다른 경로를 여럿 잡아라"에 해당하는 테스트). 인가 실패(403/401), 강등 즉시 차단
 * (블랙리스트), 리프레시로 새 역할 반영, 오류 계약(errorCode/traceId/detail 없음)을 한 흐름에서 검증한다.
 *
 * 최초 관리자는 저장소에 직접 role=ADMIN 으로 만든다 — 운영에서도 최초 한 명만 DB 로
 * 만드는 것이 확정된 방침이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class UserRoleChangeIntegrationTest {

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

    private fun refresh(refreshToken: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}"""),
        )

    private fun changeRole(targetId: Long, role: String, accessToken: String?): ResultActions {
        val requestBuilder = post("/api/admin/users/$targetId/role")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"role":"$role"}""")
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

    private fun roleClaimOf(accessToken: String): String {
        // 서명 검증이 아니라 클레임 확인이 목적이므로 페이로드만 디코딩한다.
        val payload = accessToken.split(".")[1]
        val decoded = String(Base64.getUrlDecoder().decode(payload))
        return JsonPath.read(decoded, "$.role")
    }

    @Test
    fun `관리자가 다른 사용자를 강등하면 옛 액세스 토큰은 즉시 끊기고 재로그인 없이 리프레시로만 새 역할이 반영된다`() {
        val password = "password123"

        // 최초 관리자 A 는 DB 로 직접 부트스트랩한다.
        val usernameA = uniqueUsername()
        val idA = idFromLocation(signUp(usernameA, password).andExpect(status().isCreated))
        promoteToAdmin(idA)
        val accessTokenA = accessTokenOf(login(usernameA, password).andExpect(status().isOk))

        // 일반 유저 B.
        val usernameB = uniqueUsername()
        val idB = idFromLocation(signUp(usernameB, password).andExpect(status().isCreated))
        val loginB = login(usernameB, password).andExpect(status().isOk)
        val accessTokenB = accessTokenOf(loginB)

        // 1. 인증 헤더 없이 관리자 API 호출 → 401, errorCode/traceId 있고 detail 없음.
        val unauthenticated = changeRole(idB, "ADMIN", accessToken = null)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())
            .andReturn()
        assertThat(unauthenticated.response.getHeader("X-Trace-Id"))
            .isEqualTo(JsonPath.read<String>(unauthenticated.response.contentAsString, "$.traceId"))

        // 2. 일반 유저 B 로는 403.
        changeRole(idB, "ADMIN", accessToken = accessTokenB)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())

        // 3. B 를 ADMIN 으로 승격(저장소 직접) 후 재로그인해야 관리자 API 가 통한다.
        // A 를 건드리지 않기 위해 자기 자신을 ADMIN 으로 다시 지정하는 무해한 호출로 200/204 를 확인한다.
        promoteToAdmin(idB)
        val reloginB = login(usernameB, password).andExpect(status().isOk)
        val accessTokenBAdmin = accessTokenOf(reloginB)

        changeRole(idB, "ADMIN", accessToken = accessTokenBAdmin)
            .andExpect(status().isNoContent)

        // 4. 관리자 A 가 B 를 USER 로 강등한다.
        changeRole(idB, "USER", accessToken = accessTokenA)
            .andExpect(status().isNoContent)

        // 5. B 가 들고 있던 옛(ADMIN 시절) 액세스 토큰은 블랙리스트로 즉시 401.
        changeRole(idA, "USER", accessToken = accessTokenBAdmin)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))

        // 6. B 가 재로그인 없이 refresh 만으로 새 토큰을 받으면 role 클레임이 USER 다.
        val refreshTokenB = JsonPath.read<String>(reloginB.andReturn().response.contentAsString, "$.refreshToken")
        val refreshed = refresh(refreshTokenB).andExpect(status().isOk)
        val newAccessTokenB = accessTokenOf(refreshed)
        assertThat(roleClaimOf(newAccessTokenB)).isEqualTo("USER")

        // 7. 그 새 토큰으로는 관리자 API 가 403 이다. 강등 이후 재로그인은 없었다.
        changeRole(idA, "USER", accessToken = newAccessTokenB)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }
}
