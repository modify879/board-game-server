package com.jsm.boardgame.user.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.presentation.config.ProfileImageProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * `user` 컨텍스트 회원가입/조회 API 통합 테스트.
 *
 * 테스트 간 격리는 각 테스트가 고유한 username/nickname 을 쓰는 방식으로 확보한다.
 * JUnit 은 테스트 메서드 실행 순서를 보장하지 않으므로, "가입 후 조회" 처럼 순서에 의존하는
 * 시나리오는 하나의 테스트 안에서 이어서 수행한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class UserApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var profileImageProperties: ProfileImageProperties

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

    private fun login(username: String, password: String): String {
        val body = """{"username":"$username","password":"$password"}"""
        val result = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk).andReturn()
        return JsonPath.read(result.response.contentAsString, "$.accessToken")
    }

    private fun signUpAndLogin(
        username: String = uniqueUsername(),
        password: String = "password123",
        nickname: String = uniqueNickname(),
    ): String {
        signUp(signUpBody(username = username, password = password, nickname = nickname))
            .andExpect(status().isCreated)
        return login(username, password)
    }

    @Test
    fun `유효한 정보로 회원가입하면 201과 Location 헤더를 응답하고, 그 위치를 조회하면 가입한 정보와 기본 프로필 이미지를 응답한다`() {
        val username = uniqueUsername()
        val password = "password123"
        val nickname = uniqueNickname()

        val signUpResult = signUp(signUpBody(username = username, password = password, nickname = nickname))
            .andExpect(status().isCreated)

        val location = locationOf(signUpResult)
        assertThat(location).matches("/api/users/\\d+")

        val id = idFromLocation(location)
        val accessToken = login(username, password)

        mockMvc.perform(
            get("/api/users/$id")
                .header("Authorization", "Bearer $accessToken"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.nickname").value(nickname))
            .andExpect(jsonPath("$.profileImageUrl").value(profileImageProperties.defaultUrl))
    }

    @Test
    fun `이미 사용 중인 아이디로 가입하면 409와 DUPLICATE_USERNAME 을 응답한다`() {
        val username = uniqueUsername()
        signUp(signUpBody(username = username)).andExpect(status().isCreated)

        signUp(signUpBody(username = username))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_USERNAME"))
    }

    @Test
    fun `이미 사용 중인 닉네임으로 가입하면 409와 DUPLICATE_NICKNAME 을 응답한다`() {
        val nickname = uniqueNickname()
        signUp(signUpBody(nickname = nickname)).andExpect(status().isCreated)

        signUp(signUpBody(nickname = nickname))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_NICKNAME"))
    }

    @Test
    fun `비밀번호 확인이 일치하지 않으면 400과 PASSWORD_CONFIRM_MISMATCH 를 응답한다`() {
        signUp(signUpBody(password = "password123", passwordConfirm = "password456"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PASSWORD_CONFIRM_MISMATCH"))
    }

    @Test
    fun `한글 25자(75바이트) 비밀번호로 가입하면 400과 PASSWORD_TOO_LONG 을 응답한다`() {
        val longPassword = "가".repeat(25)

        signUp(signUpBody(password = longPassword, passwordConfirm = longPassword))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PASSWORD_TOO_LONG"))
    }

    @Test
    fun `닉네임에 제로폭 문자가 있으면 400과 NICKNAME_FORBIDDEN_CHARACTER 를 응답한다`() {
        val nicknameWithZeroWidthSpace = "ad" + "\u200B" + "min"

        signUp(signUpBody(nickname = nicknameWithZeroWidthSpace))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NICKNAME_FORBIDDEN_CHARACTER"))
    }

    @Test
    fun `오류 응답은 problem+json 이며 detail 없이 traceId 를 헤더와 동일하게 담는다`() {
        val username = uniqueUsername()
        signUp(signUpBody(username = username)).andExpect(status().isCreated)

        val result = signUp(signUpBody(username = username))
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_USERNAME"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())
            .andReturn()

        val headerTraceId = result.response.getHeader("X-Trace-Id")
        val bodyTraceId = JsonPath.read<String>(result.response.contentAsString, "$.traceId")

        assertThat(headerTraceId).isNotBlank()
        assertThat(bodyTraceId).isEqualTo(headerTraceId)
    }

    @Test
    fun `존재하지 않는 사용자를 조회하면 404와 USER_NOT_FOUND 를 응답한다`() {
        val accessToken = signUpAndLogin()

        mockMvc.perform(
            get("/api/users/999999")
                .header("Authorization", "Bearer $accessToken"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
    }

    @Test
    fun `잘못된 JSON 본문은 스프링이 직접 던지는 예외도 동일한 오류 계약(errorCode)을 따르게 한다`() {
        mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{이것은 유효한 JSON 이 아니다"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
            // 스프링이 채운 영문 detail 을 핸들러가 지웠는지 확인한다 (규칙 8).
            .andExpect(jsonPath("$.detail").doesNotExist())
    }

    @Test
    fun `지원하지 않는 HTTP 메서드로 요청하면 405와 REQUEST_INVALID 를 응답하고 detail 을 노출하지 않는다`() {
        val accessToken = signUpAndLogin()

        mockMvc.perform(
            delete("/api/users")
                .header("Authorization", "Bearer $accessToken"),
        )
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())
    }

    @Test
    fun `존재하지 않는 경로로 요청하면 404와 REQUEST_INVALID 를 응답하고 detail 을 노출하지 않는다`() {
        val accessToken = signUpAndLogin()

        mockMvc.perform(
            get("/api/nonexistent")
                .header("Authorization", "Bearer $accessToken"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())
    }

    @Test
    fun `경로 변수 타입이 일치하지 않으면 400과 REQUEST_INVALID 를 응답하고 detail 을 노출하지 않는다`() {
        val accessToken = signUpAndLogin()

        mockMvc.perform(
            get("/api/users/abc")
                .header("Authorization", "Bearer $accessToken"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())
    }
}
