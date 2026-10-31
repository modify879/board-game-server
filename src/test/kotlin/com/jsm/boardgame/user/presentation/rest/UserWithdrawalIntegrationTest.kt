package com.jsm.boardgame.user.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@IntegrationTest
class UserWithdrawalIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var adjustWallet: AdjustWalletBalanceUseCase

    private val password = "password123"

    private class Account(val id: Long, val username: String, val nickname: String, val accessToken: String)

    private fun signUp(username: String, nickname: String): ResultActions =
        mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password","passwordConfirm":"$password","nickname":"$nickname"}"""),
        )

    private fun login(username: String, pw: String = password): ResultActions =
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$pw"}"""),
        )

    private fun signUpAndLogin(): Account {
        val username = "u" + UniqueNames.suffix()
        val nickname = "n" + UniqueNames.suffix()
        val id = signUp(username, nickname).andExpect(status().isCreated)
            .andReturn().response.getHeader("Location")!!.substringAfterLast("/").toLong()
        val token = JsonPath.read<String>(login(username).andReturn().response.contentAsString, "$.accessToken")
        return Account(id, username, nickname, token)
    }

    private fun withdraw(token: String?, pw: String = password): ResultActions =
        mockMvc.perform(
            post("/api/users/me/withdrawal")
                .apply { if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"password":"$pw"}"""),
        )

    @Test
    fun `탈퇴하면 204 와 쿠키 삭제가 오고 이전 토큰·로그인·프로필 조회가 모두 막히며 닉네임만 다시 쓸 수 있다`() {
        val account = signUpAndLogin()
        val bystander = signUpAndLogin()

        val result = withdraw(account.accessToken).andExpect(status().isNoContent).andReturn()
        assertThat(result.response.getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=;").contains("Max-Age=0")

        mockMvc.perform(get("/api/users/${bystander.id}").header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isUnauthorized)
        login(account.username)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("LOGIN_FAILED"))
        mockMvc.perform(get("/api/users/${account.id}").header(HttpHeaders.AUTHORIZATION, "Bearer ${bystander.accessToken}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))

        signUp(account.username, "n" + UniqueNames.suffix())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_USERNAME"))
        signUp("u" + UniqueNames.suffix(), account.nickname).andExpect(status().isCreated)
    }

    @Test
    fun `비밀번호가 틀리면 400 CURRENT_PASSWORD_MISMATCH 이고 탈퇴되지 않는다`() {
        val account = signUpAndLogin()

        withdraw(account.accessToken, pw = "wrongpassword")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("CURRENT_PASSWORD_MISMATCH"))

        login(account.username).andExpect(status().isOk)
    }

    @Test
    fun `지갑에 잔액이 있으면 409 WITHDRAWAL_WALLET_NOT_EMPTY 이고 탈퇴되지 않는다`() {
        val account = signUpAndLogin()
        adjustWallet.adjust(AdjustWalletBalanceCommand(account.id, 1_000, "테스트", account.id, "k-${UniqueNames.suffix()}"))

        withdraw(account.accessToken)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("WITHDRAWAL_WALLET_NOT_EMPTY"))

        login(account.username).andExpect(status().isOk)
    }

    @Test
    fun `토큰 없이 탈퇴하면 401 이다`() {
        withdraw(null).andExpect(status().isUnauthorized)
    }
}
