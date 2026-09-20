package com.jsm.boardgame.wallet.presentation.rest

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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * `wallet` 컨텍스트의 관리자 REST API 통합 테스트.
 * 인가(403), 그리고 승인·반려·취소·조정으로 실제 잔액과 원장이 맞물려 움직이는지를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminWalletApiIntegrationTest {

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

    private fun login(username: String, password: String): String {
        val result = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        return JsonPath.read(result.response.contentAsString, "$.accessToken")
    }

    private fun signUpAndLogin(): Pair<Long, String> {
        val username = uniqueUsername()
        val password = "password123"
        val id = idFromLocation(signUp(username, password).andExpect(status().isCreated))
        return id to login(username, password)
    }

    /** 최초 관리자는 저장소에 직접 role=ADMIN 으로 만든다(UserRoleChangeIntegrationTest 와 같은 방식). */
    private fun promoteToAdmin(userId: Long) {
        val user = userRepository.findById(UserId(userId)) ?: error("사용자를 찾을 수 없다: $userId")
        user.changeRole(UserRole.ADMIN)
        userRepository.save(user)
    }

    /**
     * 승격 후에 로그인한다 — 역할 변경은 그 즉시 기존 액세스 토큰을 블랙리스트로 끊으므로
     * (14927b5), 로그인 먼저 하고 나중에 승격하면 그 토큰은 곧바로 무효가 된다.
     */
    private fun signUpAdminAndLogin(): Pair<Long, String> {
        val username = uniqueUsername()
        val password = "password123"
        val id = idFromLocation(signUp(username, password).andExpect(status().isCreated))
        promoteToAdmin(id)
        return id to login(username, password)
    }

    private fun authGet(url: String, accessToken: String): ResultActions =
        mockMvc.perform(get(url).header("Authorization", "Bearer $accessToken"))

    private fun authPost(url: String, accessToken: String, body: String? = null): ResultActions {
        val builder = post(url).header("Authorization", "Bearer $accessToken")
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun requestDeposit(accessToken: String, amount: Int): Long {
        val result = authPost("/api/wallet/deposit-requests", accessToken, """{"amount":$amount}""")
            .andExpect(status().isCreated)
            .andReturn()
        return JsonPath.read<Int>(result.response.contentAsString, "$.requestId").toLong()
    }

    private fun requestWithdrawal(accessToken: String, amount: Int): Long {
        val body = """{"amount":$amount,"bankName":"국민은행","accountNumber":"11122233344","accountHolder":"홍길동"}"""
        val result = authPost("/api/wallet/withdrawal-requests", accessToken, body)
            .andExpect(status().isCreated)
            .andReturn()
        return JsonPath.read<Int>(result.response.contentAsString, "$.requestId").toLong()
    }

    private fun balanceOf(accessToken: String): Long {
        val result = authGet("/api/wallet", accessToken).andExpect(status().isOk).andReturn()
        return JsonPath.read<Int>(result.response.contentAsString, "$.balance").toLong()
    }

    @Test
    fun `일반 사용자로 관리자 API 를 호출하면 403과 ACCESS_DENIED 를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authGet("/api/admin/deposit-requests", accessToken)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    @Test
    fun `관리자가 충전 요청을 승인하면 잔액이 요청 금액만큼 늘고, 같은 요청을 다시 승인하면 409를 응답하며 잔액이 두 배가 되지 않는다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (_, userToken) = signUpAndLogin()

        val requestId = requestDeposit(userToken, 5000)

        authPost("/api/admin/deposit-requests/$requestId/approve", adminToken)
            .andExpect(status().isNoContent)

        assertThat(balanceOf(userToken)).isEqualTo(5000)

        authPost("/api/admin/deposit-requests/$requestId/approve", adminToken)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DEPOSIT_REQUEST_ALREADY_PROCESSED"))

        assertThat(balanceOf(userToken)).isEqualTo(5000)
    }

    @Test
    fun `creditedAmount 를 명시해 승인하면 그 금액이 잔액에 반영된다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (_, userToken) = signUpAndLogin()

        val requestId = requestDeposit(userToken, 5000)

        authPost("/api/admin/deposit-requests/$requestId/approve", adminToken, """{"creditedAmount":9000}""")
            .andExpect(status().isNoContent)

        assertThat(balanceOf(userToken)).isEqualTo(9000)
    }

    @Test
    fun `승인된 충전 원장에 DEPOSIT 엔트리가 보이고 balanceAfter 가 맞다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (_, userToken) = signUpAndLogin()

        val requestId = requestDeposit(userToken, 5000)
        authPost("/api/admin/deposit-requests/$requestId/approve", adminToken).andExpect(status().isNoContent)

        val result = authGet("/api/wallet/ledger", userToken).andExpect(status().isOk).andReturn()
        val types = JsonPath.read<List<String>>(result.response.contentAsString, "$.content[*].type")
        val balancesAfter = JsonPath.read<List<Int>>(result.response.contentAsString, "$.content[*].balanceAfter")

        assertThat(types).contains("DEPOSIT")
        assertThat(balancesAfter[types.indexOf("DEPOSIT")]).isEqualTo(5000)

        // 페이지 응답은 PagedModel 형태다 — content 와 page 메타가 분리되어 있어야 한다
        // (spring.data.web.pageable.serialization-mode: via_dto). Page 를 그대로 직렬화하면
        // 응답이 PageImpl 의 내부 구조에 묶여 계약이 불안정해진다.
        assertThat(JsonPath.read<Int>(result.response.contentAsString, "$.page.totalElements")).isEqualTo(1)
    }

    @Test
    fun `환전을 요청하면 잔액이 즉시 0이 되고 원장에 WITHDRAWAL_HOLD 가 남으며, 관리자가 반려하면 잔액이 복구되고 WITHDRAWAL_REFUND 가 남는다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (_, userToken) = signUpAndLogin()

        val depositRequestId = requestDeposit(userToken, 10_000)
        authPost("/api/admin/deposit-requests/$depositRequestId/approve", adminToken).andExpect(status().isNoContent)
        assertThat(balanceOf(userToken)).isEqualTo(10_000)

        val withdrawalRequestId = requestWithdrawal(userToken, 4_000)
        assertThat(balanceOf(userToken)).isEqualTo(6_000)

        authPost("/api/admin/withdrawal-requests/$withdrawalRequestId/reject", adminToken, """{"reason":"계좌 확인 불가"}""")
            .andExpect(status().isNoContent)

        assertThat(balanceOf(userToken)).isEqualTo(10_000)

        val result = authGet("/api/wallet/ledger", userToken).andExpect(status().isOk).andReturn()
        val types = JsonPath.read<List<String>>(result.response.contentAsString, "$.content[*].type")
        assertThat(types).contains("WITHDRAWAL_HOLD", "WITHDRAWAL_REFUND")
    }

    @Test
    fun `관리자 조정으로 잔액을 회수하면 ADMIN_ADJUSTMENT_DEBIT 엔트리가 남고 memo 가 사유다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (targetId, userToken) = signUpAndLogin()

        val depositRequestId = requestDeposit(userToken, 5000)
        authPost("/api/admin/deposit-requests/$depositRequestId/approve", adminToken).andExpect(status().isNoContent)

        authPost("/api/admin/wallets/$targetId/adjustments", adminToken, """{"amount":-1000,"reason":"부정 사용 회수"}""")
            .andExpect(status().isNoContent)

        assertThat(balanceOf(userToken)).isEqualTo(4000)

        val result = authGet("/api/wallet/ledger", userToken).andExpect(status().isOk).andReturn()
        val types = JsonPath.read<List<String>>(result.response.contentAsString, "$.content[*].type")
        val memos = JsonPath.read<List<String?>>(result.response.contentAsString, "$.content[*].memo")
        val index = types.indexOf("ADMIN_ADJUSTMENT_DEBIT")

        assertThat(index).isNotEqualTo(-1)
        assertThat(memos[index]).isEqualTo("부정 사용 회수")
    }

    @Test
    fun `사유 없이 조정하면 400과 ADJUSTMENT_REASON_BLANK 를 응답한다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (targetId, _) = signUpAndLogin()

        authPost("/api/admin/wallets/$targetId/adjustments", adminToken, """{"amount":1000,"reason":""}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("ADJUSTMENT_REASON_BLANK"))
    }

    @Test
    // 이 URL 은 403 테스트에서만 불리고 있어 성공 경로가 통째로 비어 있었다.
    fun `관리자 충전 요청 목록 조회는 status 로 필터링한다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (_, userToken) = signUpAndLogin()

        val pendingId = requestDeposit(userToken, 4_000)
        val approvedId = requestDeposit(userToken, 6_000)
        authPost("/api/admin/deposit-requests/$approvedId/approve", adminToken).andExpect(status().isNoContent)

        val all = authGet("/api/admin/deposit-requests", adminToken).andExpect(status().isOk).andReturn()
        val allIds = JsonPath.read<List<Int>>(all.response.contentAsString, "$.content[*].id")
        assertThat(allIds).contains(pendingId.toInt(), approvedId.toInt())

        val pendingOnly = authGet("/api/admin/deposit-requests?status=PENDING", adminToken)
            .andExpect(status().isOk)
            .andReturn()
        val pendingIds = JsonPath.read<List<Int>>(pendingOnly.response.contentAsString, "$.content[*].id")
        val pendingStatuses = JsonPath.read<List<String>>(pendingOnly.response.contentAsString, "$.content[*].status")

        assertThat(pendingIds).contains(pendingId.toInt())
        assertThat(pendingIds).doesNotContain(approvedId.toInt())
        assertThat(pendingStatuses).allMatch { it == "PENDING" }
    }

    @Test
    fun `관리자 환전 요청 목록 조회는 status 로 필터링한다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val (_, userToken) = signUpAndLogin()

        val depositRequestId = requestDeposit(userToken, 10_000)
        authPost("/api/admin/deposit-requests/$depositRequestId/approve", adminToken).andExpect(status().isNoContent)

        val pendingId = requestWithdrawal(userToken, 3_000)
        val rejectedId = requestWithdrawal(userToken, 2_000)
        authPost("/api/admin/withdrawal-requests/$rejectedId/reject", adminToken, """{"reason":"계좌 확인 불가"}""")
            .andExpect(status().isNoContent)

        val all = authGet("/api/admin/withdrawal-requests", adminToken).andExpect(status().isOk).andReturn()
        val allIds = JsonPath.read<List<Int>>(all.response.contentAsString, "$.content[*].id")
        assertThat(allIds).contains(pendingId.toInt(), rejectedId.toInt())

        val pendingOnly = authGet("/api/admin/withdrawal-requests?status=PENDING", adminToken)
            .andExpect(status().isOk)
            .andReturn()
        val pendingIds = JsonPath.read<List<Int>>(pendingOnly.response.contentAsString, "$.content[*].id")
        val pendingStatuses = JsonPath.read<List<String>>(pendingOnly.response.contentAsString, "$.content[*].status")

        assertThat(pendingIds).contains(pendingId.toInt())
        assertThat(pendingIds).doesNotContain(rejectedId.toInt())
        assertThat(pendingStatuses).allMatch { it == "PENDING" }
    }

    @Test
    fun `존재하지 않는 대상 사용자로 조정하면 400과 WALLET_OWNER_NOT_FOUND 를 응답하고 detail 이 없다`() {
        val (_, adminToken) = signUpAdminAndLogin()
        val nonExistentUserId = 987_654_321L

        authPost("/api/admin/wallets/$nonExistentUserId/adjustments", adminToken, """{"amount":1000,"reason":"사유"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("WALLET_OWNER_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").doesNotExist())
    }
}
