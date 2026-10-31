package com.jsm.boardgame.holdem.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.model.UserId as UserDomainId
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalUseCase
import com.jsm.boardgame.wallet.application.query.service.DepositRequestQueryService
import com.jsm.boardgame.wallet.application.query.service.WalletQueryService
import com.jsm.boardgame.wallet.application.query.service.WithdrawalRequestQueryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** qa 프로필의 계정 리셋 API.. */
@IntegrationTest
@ActiveProfiles("qa")
@TestPropertySource(
    properties = ["app.holdem.turn-timeout=1h", "app.holdem.idle-table-ttl=1h"],
)
class QaResetIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var tables: HoldemTableRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var recentDepartures: RecentDepartures
    @Autowired private lateinit var adjust: AdjustWalletBalanceUseCase
    @Autowired private lateinit var requestDeposit: RequestDepositUseCase
    @Autowired private lateinit var requestWithdrawal: RequestWithdrawalUseCase
    @Autowired private lateinit var deposits: DepositRequestQueryService
    @Autowired private lateinit var withdrawals: WithdrawalRequestQueryService
    @Autowired private lateinit var wallets: WalletQueryService

    private fun signUp(): Pair<String, Long> {
        val username = "u" + UniqueNames.suffix()
        val res = mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"password123","passwordConfirm":"password123","nickname":"n${UniqueNames.suffix()}"}"""),
        ).andExpect(status().isCreated).andReturn()
        return username to res.response.getHeader("Location")!!.substringAfterLast("/").toLong()
    }

    private fun adminToken(): String {
        val (username, id) = signUp()
        userRepository.findById(UserDomainId(id))!!.also { it.changeRole(UserRole.ADMIN); userRepository.save(it) }
        return JsonPath.read(
            mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username":"$username","password":"password123"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            "$.accessToken",
        )
    }

    private fun reset(token: String, vararg usernames: String): ResultActions =
        mockMvc.perform(
            post("/api/admin/qa/reset").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .content("""{"usernames":[${usernames.joinToString(",") { "\"$it\"" }}]}"""),
        )

    @Test
    fun `리셋하면 재착석 의무가 지워지고 대기 요청이 반려되고 잔액이 기준으로 돌아온다`() {
        val token = adminToken()
        val (username, id) = signUp()
        adjust.adjust(AdjustWalletBalanceCommand(id, 3_000_000, "seed", 0, "k-${UniqueNames.suffix()}"))
        recentDepartures.record(UserId(id), Chips.of(5_000))
        requestDeposit.request(RequestDepositCommand(id, 1_000, "d-${UniqueNames.suffix()}"))
        requestWithdrawal.request(RequestWithdrawalCommand(id, 500_000, "bank", "123456789012", "holder", "w-${UniqueNames.suffix()}"))
        assertThat(wallets.findBalance(id).balance).isEqualTo(2_500_000) // 환전 보류금 차감

        reset(token, username).andExpect(status().isNoContent)

        assertThat(recentDepartures.obligationOf(UserId(id))).isNull()
        assertThat(deposits.findMine(id, PageRequest.of(0, 10)).content.map { it.status }).containsOnly("REJECTED")
        assertThat(withdrawals.findMine(id, PageRequest.of(0, 10)).content.map { it.status }).containsOnly("REJECTED")
        assertThat(wallets.findBalance(id).balance).isEqualTo(10_000_000)
    }

    @Test
    fun `대기 요청이 한 페이지를 넘어도 전부 반려한다`() {
        val token = adminToken()
        val (username, id) = signUp()
        repeat(201) { requestDeposit.request(RequestDepositCommand(id, 1_000, "d$it-${UniqueNames.suffix()}")) }

        reset(token, username).andExpect(status().isNoContent)

        val all = deposits.findMine(id, PageRequest.of(0, 500)).content
        assertThat(all).hasSize(201)
        assertThat(all.map { it.status }).containsOnly("REJECTED")
    }

    @Test
    fun `없는 아이디가 섞이면 404 USER_NOT_FOUND 이고 다른 계정도 바뀌지 않는다`() {
        val token = adminToken()
        val (username, id) = signUp()
        recentDepartures.record(UserId(id), Chips.of(5_000))

        reset(token, username, "nobody-" + UniqueNames.suffix())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))

        assertThat(recentDepartures.obligationOf(UserId(id))).isNotNull()
    }

    @Test
    fun `착석한 사용자가 섞이면 409 ALREADY_SEATED 이고 다른 계정도 바뀌지 않는다`() {
        val token = adminToken()
        val (username, id) = signUp()
        val (seatedName, seatedId) = signUp()
        recentDepartures.record(UserId(id), Chips.of(5_000))
        tables.save(HoldemTable.create("qa-${UniqueNames.suffix()}", TestTablePolicy).also { it.sitDown(UserId(seatedId), Chips.of(10_000)) })

        reset(token, username, seatedName)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ALREADY_SEATED"))

        assertThat(recentDepartures.obligationOf(UserId(id))).isNotNull()
    }

    @Test
    fun `일반 사용자는 403 이다`() {
        val (username, _) = signUp()
        val token: String = JsonPath.read(
            mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username":"$username","password":"password123"}"""),
            ).andReturn().response.contentAsString,
            "$.accessToken",
        )
        reset(token, username).andExpect(status().isForbidden)
    }
}
