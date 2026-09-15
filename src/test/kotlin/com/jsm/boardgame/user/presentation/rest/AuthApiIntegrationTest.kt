package com.jsm.boardgame.user.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * `user` 컨텍스트 인증(로그인/토큰 갱신/로그아웃) API 통합 테스트.
 *
 * 테스트 간 격리는 각 테스트가 고유한 username/nickname 을 쓰는 방식으로 확보한다.
 * JUnit 은 테스트 메서드 실행 순서를 보장하지 않으므로, "로그인 후 토큰 사용" 처럼 순서에
 * 의존하는 시나리오는 하나의 테스트 안에서 이어서 수행한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun uniqueUsername(): String =
        "u" + UUID.randomUUID().toString().replace("-", "").take(9).lowercase()

    private fun uniqueNickname(): String =
        "n" + UUID.randomUUID().toString().replace("-", "").take(5).lowercase()

    private fun signUpBody(
        username: String = uniqueUsername(),
        password: String = "password123",
        passwordConfirm: String = password,
        nickname: String = uniqueNickname(),
    ): String =
        """{"username":"$username","password":"$password","passwordConfirm":"$passwordConfirm","nickname":"$nickname"}"""

    private fun signUp(body: String): ResultActions =
        mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun locationOf(result: ResultActions): String =
        result.andReturn().response.getHeader("Location")
            ?: error("Location 헤더가 없다")

    private fun idFromLocation(location: String): Long =
        location.substringAfterLast("/").toLong()

    private fun login(username: String, password: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        )

    private fun refresh(refreshToken: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}"""),
        )

    private fun logout(accessToken: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
        )

    private fun getProfile(id: Long, accessToken: String? = null): ResultActions {
        val requestBuilder = get("/api/users/$id")
        if (accessToken != null) {
            requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }
        return mockMvc.perform(requestBuilder)
    }

    private fun accessTokenOf(result: ResultActions): String =
        JsonPath.read<String>(result.andReturn().response.contentAsString, "$.accessToken")

    private fun refreshTokenOf(result: ResultActions): String =
        JsonPath.read<String>(result.andReturn().response.contentAsString, "$.refreshToken")

    @Test
    fun `정상적으로 로그인하면 200 과 함께 액세스 리프레시 토큰을 응답한다`() {
        val username = uniqueUsername()
        val password = "password123"
        signUp(signUpBody(username = username, password = password)).andExpect(status().isCreated)

        login(username, password)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.accessTokenExpiresAt").isNotEmpty)
    }

    @Test
    fun `비밀번호가 틀리면 401 과 errorCode LOGIN_FAILED 를 응답한다`() {
        val username = uniqueUsername()
        signUp(signUpBody(username = username, password = "password123")).andExpect(status().isCreated)

        login(username, "wrongpassword")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("LOGIN_FAILED"))
    }

    @Test
    fun `존재하지 않는 아이디로 로그인하면 비밀번호가 틀린 경우와 같은 errorCode LOGIN_FAILED 를 응답한다`() {
        val username = uniqueUsername()
        signUp(signUpBody(username = username, password = "password123")).andExpect(status().isCreated)

        val wrongPasswordResult = login(username, "wrongpassword")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("LOGIN_FAILED"))
            .andReturn()

        val unknownUsernameResult = login(uniqueUsername(), "password123")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("LOGIN_FAILED"))
            .andReturn()

        val wrongPasswordErrorCode =
            JsonPath.read<String>(wrongPasswordResult.response.contentAsString, "$.errorCode")
        val unknownUsernameErrorCode =
            JsonPath.read<String>(unknownUsernameResult.response.contentAsString, "$.errorCode")

        assertThat(unknownUsernameErrorCode).isEqualTo(wrongPasswordErrorCode)
    }

    @Test
    fun `토큰 없이 GET api users id 를 호출하면 401 과 함께 problem+json, errorCode, traceId, detail 없음을 모두 응답한다`() {
        val username = uniqueUsername()
        val signUpResult = signUp(signUpBody(username = username)).andExpect(status().isCreated)
        val id = idFromLocation(locationOf(signUpResult))

        val result = getProfile(id)
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())
            .andReturn()

        val headerTraceId = result.response.getHeader("X-Trace-Id")
        val bodyTraceId = JsonPath.read<String>(result.response.contentAsString, "$.traceId")

        assertThat(headerTraceId).isNotBlank()
        assertThat(bodyTraceId).isEqualTo(headerTraceId)
    }

    @Test
    fun `유효한 토큰으로 GET api users id 를 호출하면 200 을 응답한다`() {
        val username = uniqueUsername()
        val password = "password123"
        val signUpResult = signUp(signUpBody(username = username, password = password))
            .andExpect(status().isCreated)
        val id = idFromLocation(locationOf(signUpResult))

        val loginResult = login(username, password).andExpect(status().isOk)
        val accessToken = accessTokenOf(loginResult)

        getProfile(id, accessToken)
            .andExpect(status().isOk)
    }

    @Test
    fun `리프레시하면 200 과 함께 이전과 다른 액세스 리프레시 토큰을 응답한다`() {
        val username = uniqueUsername()
        val password = "password123"
        signUp(signUpBody(username = username, password = password)).andExpect(status().isCreated)

        val loginResult = login(username, password).andExpect(status().isOk)
        val oldAccessToken = accessTokenOf(loginResult)
        val oldRefreshToken = refreshTokenOf(loginResult)

        val refreshResult = refresh(oldRefreshToken)
            .andExpect(status().isOk)
            .andReturn()

        val newAccessToken = JsonPath.read<String>(refreshResult.response.contentAsString, "$.accessToken")
        val newRefreshToken = JsonPath.read<String>(refreshResult.response.contentAsString, "$.refreshToken")

        assertThat(newAccessToken).isNotEqualTo(oldAccessToken)
        assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken)
    }

    @Test
    fun `리프레시 토큰 재사용이 탐지되면 기존 액세스 토큰도 즉시 차단된다`() {
        val username = uniqueUsername()
        val password = "password123"
        val signUpResult = signUp(signUpBody(username = username, password = password))
            .andExpect(status().isCreated)
        val id = idFromLocation(locationOf(signUpResult))

        val loginResult = login(username, password).andExpect(status().isOk)
        val refreshTokenR = refreshTokenOf(loginResult)

        // R → R2, 새 액세스 A2
        val firstRefreshResult = refresh(refreshTokenR).andExpect(status().isOk)
        val refreshTokenR2 = refreshTokenOf(firstRefreshResult)

        // 유예(5초)를 벗어나도록 한 번 더 갱신한다(R2 → R3) — 이제 R 은 두 세대 전이라
        // 유예와 무관하게 재사용 탐지가 그대로 동작해야 한다. 이 시점의 액세스 토큰이 A3.
        val secondRefreshResult = refresh(refreshTokenR2).andExpect(status().isOk)
        val accessTokenA3 = accessTokenOf(secondRefreshResult)

        // R 로 갱신 시도 → 재사용 탐지로 401
        refresh(refreshTokenR)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_INVALID"))

        // 재사용 탐지 시점에 살아 있던 액세스 토큰(A3)도 즉시 블랙리스트에 들어가 더 이상 통하면 안 된다.
        getProfile(id, accessTokenA3)
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `리프레시 토큰을 Bearer 로 쓰면 401 이다`() {
        val username = uniqueUsername()
        val password = "password123"
        val signUpResult = signUp(signUpBody(username = username, password = password))
            .andExpect(status().isCreated)
        val id = idFromLocation(locationOf(signUpResult))

        val loginResult = login(username, password).andExpect(status().isOk)
        val refreshToken = refreshTokenOf(loginResult)

        getProfile(id, refreshToken)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
    }

    @Test
    fun `로그아웃하면 204 를 응답하고, 그 이후 로그아웃 전 액세스 토큰으로 조회하면 401 을 응답한다`() {
        val username = uniqueUsername()
        val password = "password123"
        val signUpResult = signUp(signUpBody(username = username, password = password))
            .andExpect(status().isCreated)
        val id = idFromLocation(locationOf(signUpResult))

        val loginResult = login(username, password).andExpect(status().isOk)
        val accessToken = accessTokenOf(loginResult)

        getProfile(id, accessToken).andExpect(status().isOk)

        logout(accessToken).andExpect(status().isNoContent)

        getProfile(id, accessToken)
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `재로그인하면 이전 로그인에서 발급된 액세스 토큰은 더 이상 통하지 않는다`() {
        val username = uniqueUsername()
        val password = "password123"
        val signUpResult = signUp(signUpBody(username = username, password = password))
            .andExpect(status().isCreated)
        val id = idFromLocation(locationOf(signUpResult))

        val firstLoginResult = login(username, password).andExpect(status().isOk)
        val firstAccessToken = accessTokenOf(firstLoginResult)

        getProfile(id, firstAccessToken).andExpect(status().isOk)

        login(username, password).andExpect(status().isOk)

        getProfile(id, firstAccessToken)
            .andExpect(status().isUnauthorized)
    }
}
