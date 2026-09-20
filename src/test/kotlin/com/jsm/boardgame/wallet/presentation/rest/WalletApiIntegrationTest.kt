package com.jsm.boardgame.wallet.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * `wallet` 컨텍스트의 일반 사용자 REST API 통합 테스트.
 * 관리자 경로와 실제로 돈이 움직이는 흐름은 `AdminWalletApiIntegrationTest` 가 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WalletApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var walletRepository: WalletRepository

    @Autowired
    private lateinit var ledgerEntryRepository: LedgerEntryRepository

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

    private fun login(username: String, password: String): String {
        val result = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        return JsonPath.read(result.response.contentAsString, "$.accessToken")
    }

    /** 회원가입 후 로그인까지 마치고 (사용자 id, 액세스 토큰)을 돌려준다. */
    private fun signUpAndLogin(): Pair<Long, String> {
        val username = uniqueUsername()
        val password = "password123"
        val id = idFromLocation(signUp(username, password).andExpect(status().isCreated))
        return id to login(username, password)
    }

    private fun authGet(url: String, accessToken: String?): ResultActions {
        val builder = get(url)
        if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
        return mockMvc.perform(builder)
    }

    private fun authPost(url: String, accessToken: String, body: String? = null): ResultActions {
        val builder = post(url).header("Authorization", "Bearer $accessToken")
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun authDelete(url: String, accessToken: String): ResultActions =
        mockMvc.perform(delete(url).header("Authorization", "Bearer $accessToken"))

    /** 환전은 요청 시점에 즉시 차감되므로, 요청을 만들려면 잔액이 먼저 있어야 한다. */
    private fun fundWallet(userId: Long, amount: Long) {
        val wallet = walletRepository.findOrOpen(userId)
        val entry = wallet.record(
            type = LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT,
            amount = Money.of(amount),
            reference = LedgerReference(LedgerReferenceType.ADMIN_ADJUSTMENT, 0),
            at = Instant.now(),
        )
        walletRepository.save(wallet)
        ledgerEntryRepository.save(entry)
    }

    private fun requestWithdrawal(accessToken: String, amount: Int): Int {
        val body = """{"amount":$amount,"bankName":"국민은행","accountNumber":"11122233344","accountHolder":"홍길동"}"""
        val result = authPost("/api/wallet/withdrawal-requests", accessToken, body)
            .andExpect(status().isCreated)
            .andReturn()
        return JsonPath.read(result.response.contentAsString, "$.requestId")
    }

    @Test
    fun `지갑이 없는 사용자도 잔액 조회는 200과 잔액 0을 응답하고 지갑을 만들지 않는다`() {
        val (userId, accessToken) = signUpAndLogin()

        authGet("/api/wallet", accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balance").value(0))

        assertThat(walletRepository.findByUserId(userId)).isNull()
    }

    @Test
    fun `충전을 요청하면 201과 요청 id, 입금 계좌 안내를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authPost("/api/wallet/deposit-requests", accessToken, """{"amount":5000}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.requestId").isNumber)
            .andExpect(jsonPath("$.depositAccount.bankName").isNotEmpty)
            .andExpect(jsonPath("$.depositAccount.accountNumber").isNotEmpty)
            .andExpect(jsonPath("$.depositAccount.accountHolder").isNotEmpty)
    }

    @Test
    fun `100원 단위가 아닌 충전 요청은 400과 DEPOSIT_AMOUNT_INVALID 를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authPost("/api/wallet/deposit-requests", accessToken, """{"amount":900}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_AMOUNT_INVALID"))
    }

    @Test
    fun `최소 금액 미만의 충전 요청은 400과 DEPOSIT_AMOUNT_INVALID 를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authPost("/api/wallet/deposit-requests", accessToken, """{"amount":500}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_AMOUNT_INVALID"))
    }

    @Test
    fun `음수 금액의 충전 요청은 400과 DEPOSIT_AMOUNT_INVALID 를 응답한다 (AMOUNT_NEGATIVE 가 아니다)`() {
        val (_, accessToken) = signUpAndLogin()

        authPost("/api/wallet/deposit-requests", accessToken, """{"amount":-5000}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_AMOUNT_INVALID"))
    }

    @Test
    fun `충전 요청 목록 조회는 본인 것만 돌려준다`() {
        val (_, accessTokenA) = signUpAndLogin()
        val (_, accessTokenB) = signUpAndLogin()

        authPost("/api/wallet/deposit-requests", accessTokenA, """{"amount":5000}""").andExpect(status().isCreated)
        authPost("/api/wallet/deposit-requests", accessTokenB, """{"amount":7000}""").andExpect(status().isCreated)

        val result = authGet("/api/wallet/deposit-requests", accessTokenA)
            .andExpect(status().isOk)
            .andReturn()

        val amounts = JsonPath.read<List<Int>>(result.response.contentAsString, "$.content[*].requestedAmount")
        assertThat(amounts).containsExactly(5000)
    }

    @Test
    // 남의 요청과 없는 요청이 **같은 응답**이어야 한다. 갈리는 순간 인증된 사용자가 아무 id 나
    // 넣어보는 것만으로 남의 충전 요청이 존재하는지 열거할 수 있다.
    fun `남의 충전 요청 취소는 없는 요청과 똑같이 404 DEPOSIT_REQUEST_NOT_FOUND 다`() {
        val (_, accessTokenA) = signUpAndLogin()
        val (_, accessTokenB) = signUpAndLogin()

        val created = authPost("/api/wallet/deposit-requests", accessTokenA, """{"amount":5000}""")
            .andExpect(status().isCreated)
            .andReturn()
        val requestId = JsonPath.read<Int>(created.response.contentAsString, "$.requestId")

        authDelete("/api/wallet/deposit-requests/$requestId", accessTokenB)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_REQUEST_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").doesNotExist())

        authDelete("/api/wallet/deposit-requests/999999", accessTokenB)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_REQUEST_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").doesNotExist())
    }

    @Test
    fun `본인 충전 요청을 취소하면 204, 다시 취소하면 409와 DEPOSIT_REQUEST_ALREADY_PROCESSED 를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        val created = authPost("/api/wallet/deposit-requests", accessToken, """{"amount":5000}""")
            .andExpect(status().isCreated)
            .andReturn()
        val requestId = JsonPath.read<Int>(created.response.contentAsString, "$.requestId")

        authDelete("/api/wallet/deposit-requests/$requestId", accessToken)
            .andExpect(status().isNoContent)

        authDelete("/api/wallet/deposit-requests/$requestId", accessToken)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_REQUEST_ALREADY_PROCESSED"))
    }

    @Test
    fun `환전 요청 목록 조회는 본인 것만 돌려준다`() {
        val (userIdA, accessTokenA) = signUpAndLogin()
        val (userIdB, accessTokenB) = signUpAndLogin()
        fundWallet(userIdA, 10_000)
        fundWallet(userIdB, 10_000)

        requestWithdrawal(accessTokenA, 5000)
        requestWithdrawal(accessTokenB, 7000)

        val result = authGet("/api/wallet/withdrawal-requests", accessTokenA)
            .andExpect(status().isOk)
            .andReturn()

        val amounts = JsonPath.read<List<Int>>(result.response.contentAsString, "$.content[*].amount")
        assertThat(amounts).containsExactly(5000)
    }

    @Test
    fun `인증 헤더 없이 지갑을 조회하면 401과 AUTHENTICATION_REQUIRED 를 응답한다`() {
        authGet("/api/wallet", accessToken = null)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
    }

    @Test
    fun `오류 응답은 errorCode 와 traceId 를 담고 detail 은 없다`() {
        val (_, accessToken) = signUpAndLogin()

        val result = authPost("/api/wallet/deposit-requests", accessToken, """{"amount":900}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_AMOUNT_INVALID"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.detail").doesNotExist())
            .andReturn()

        val headerTraceId = result.response.getHeader("X-Trace-Id")
        val bodyTraceId = JsonPath.read<String>(result.response.contentAsString, "$.traceId")
        assertThat(headerTraceId).isEqualTo(bodyTraceId)
    }

    @Test
    fun `음수 금액의 환전 요청은 400과 WITHDRAWAL_AMOUNT_INVALID 를 응답한다 (AMOUNT_NEGATIVE 가 아니다)`() {
        val (_, accessToken) = signUpAndLogin()

        val body = """{"amount":-5000,"bankName":"국민은행","accountNumber":"11122233344","accountHolder":"홍길동"}"""
        authPost("/api/wallet/withdrawal-requests", accessToken, body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("WITHDRAWAL_AMOUNT_INVALID"))
    }
}
