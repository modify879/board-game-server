package com.jsm.boardgame.user.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@IntegrationTest
class UserPasswordChangeIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val oldPassword = "password123"
    private val newPassword = "newpassword456"

    private class Account(val id: Long, val username: String, val accessToken: String, val refreshToken: String)

    private fun signUpAndLogin(): Account {
        val username = "u" + UniqueNames.suffix()
        val signUp = mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$oldPassword","passwordConfirm":"$oldPassword","nickname":"n${UniqueNames.suffix()}"}"""),
        ).andExpect(status().isCreated).andReturn()
        val id = signUp.response.getHeader("Location")!!.substringAfterLast("/").toLong()
        val login = login(username, oldPassword).andExpect(status().isOk)
        return Account(id, username, accessTokenOf(login), refreshTokenOf(login))
    }

    private fun login(username: String, password: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        )

    private fun accessTokenOf(result: ResultActions): String =
        JsonPath.read(result.andReturn().response.contentAsString, "$.accessToken")

    private fun refreshTokenOf(result: ResultActions): String =
        result.andReturn().response.getHeader(HttpHeaders.SET_COOKIE)!!
            .substringAfter("refresh_token=").substringBefore(";")

    private fun changePassword(
        accessToken: String?,
        current: String = oldPassword,
        new: String = newPassword,
        confirm: String = new,
    ): ResultActions =
        mockMvc.perform(
            put("/api/users/me/password")
                .apply { if (accessToken != null) header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$current","newPassword":"$new","newPasswordConfirm":"$confirm"}"""),
        )

    private fun profile(id: Long, accessToken: String): ResultActions =
        mockMvc.perform(get("/api/users/$id").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))

    @Test
    fun `비밀번호를 바꾸면 새 토큰이 발급되고 옛 액세스 토큰과 리프레시 토큰은 쓸 수 없다`() {
        val account = signUpAndLogin()

        val changed = changePassword(account.accessToken).andExpect(status().isOk)
        val newAccessToken = accessTokenOf(changed)
        val newRefreshToken = refreshTokenOf(changed)
        assertThat(newRefreshToken).isNotEqualTo(account.refreshToken)

        profile(account.id, newAccessToken).andExpect(status().isOk)
        profile(account.id, account.accessToken).andExpect(status().isUnauthorized)

        login(account.username, newPassword).andExpect(status().isOk)
        login(account.username, oldPassword)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("LOGIN_FAILED"))

        // 마지막에 확인한다 — 옛 리프레시 제시는 재사용 탐지라 현재 세션을 통째로 폐기한다.
        mockMvc.perform(post("/api/auth/refresh").cookie(Cookie("refresh_token", account.refreshToken)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_INVALID"))
    }

    @Test
    fun `새 리프레시 토큰은 변경 직후 갱신에 쓸 수 있다`() {
        val account = signUpAndLogin()
        val newRefreshToken = refreshTokenOf(changePassword(account.accessToken).andExpect(status().isOk))

        mockMvc.perform(post("/api/auth/refresh").cookie(Cookie("refresh_token", newRefreshToken)))
            .andExpect(status().isOk)
    }

    @Test
    fun `현재 비밀번호가 틀리면 400 CURRENT_PASSWORD_MISMATCH 이고 기존 토큰은 그대로 유효하다`() {
        val account = signUpAndLogin()

        changePassword(account.accessToken, current = "wrongpassword")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("CURRENT_PASSWORD_MISMATCH"))

        profile(account.id, account.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `새 비밀번호 확인이 다르면 400 PASSWORD_CONFIRM_MISMATCH 다`() {
        val account = signUpAndLogin()

        changePassword(account.accessToken, confirm = "different456")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PASSWORD_CONFIRM_MISMATCH"))
    }

    @Test
    fun `새 비밀번호가 너무 짧으면 400 PASSWORD_TOO_SHORT 다`() {
        val account = signUpAndLogin()

        changePassword(account.accessToken, new = "short")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PASSWORD_TOO_SHORT"))
    }

    @Test
    fun `토큰 없이 비밀번호를 바꾸면 401 이다`() {
        changePassword(null).andExpect(status().isUnauthorized)
    }
}
