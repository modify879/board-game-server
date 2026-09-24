package com.jsm.boardgame.holdem.presentation.rest

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
 * `holdem` 방/좌석 REST API 통합 테스트.
 * 핸드 내부 베팅 규칙 자체는 도메인 테스트가 맡는다 — 여기는 방 생성·착석·기립·조회의
 * HTTP 계약(상태 코드, errorCode)만 검증한다. 핸드는 시작 직후 상태만 확인하고 끝까지 진행하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HoldemApiIntegrationTest {

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

    private fun balanceOf(userId: Long): Long = walletRepository.findByUserId(userId)!!.balance.amount

    private fun createTable(accessToken: String, name: String = "t-${UUID.randomUUID().toString().take(8)}"): Long {
        val result = authPost("/api/holdem/tables", accessToken, """{"name":"$name"}""")
            .andExpect(status().isCreated)
            .andReturn()
        return JsonPath.read<Int>(result.response.contentAsString, "$.tableId").toLong()
    }

    private fun sitDown(accessToken: String, tableId: Long, seatNo: Int, buyIn: Long): ResultActions =
        authPost("/api/holdem/tables/$tableId/seats", accessToken, """{"seatNo":$seatNo,"buyIn":$buyIn}""")

    private fun startHand(accessToken: String, tableId: Long): ResultActions =
        authPost("/api/holdem/tables/$tableId/hands", accessToken)

    private data class SeatedContext(val tableId: Long, val userId: Long, val accessToken: String)

    /** 한 사용자가 착석했지만 핸드는 시작하지 않은 테이블을 만든다. */
    private fun seatedWithoutHand(buyIn: Long = 10_000L, fundAmount: Long = 15_000L): SeatedContext {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, fundAmount)
        val tableId = createTable(accessToken)
        sitDown(accessToken, tableId, 1, buyIn).andExpect(status().isCreated)
        return SeatedContext(tableId, userId, accessToken)
    }

    /** 두 사용자가 착석하고 핸드가 진행 중인 테이블을 만든다. 반환값은 그중 한 명(userA) 기준이다. */
    private fun seatedWithHandInProgress(): SeatedContext {
        val (userIdA, accessTokenA) = signUpAndLogin()
        val (userIdB, accessTokenB) = signUpAndLogin()
        fundWallet(userIdA, 15_000)
        fundWallet(userIdB, 15_000)
        val tableId = createTable(accessTokenA)
        sitDown(accessTokenA, tableId, 1, 10_000).andExpect(status().isCreated)
        sitDown(accessTokenB, tableId, 2, 10_000).andExpect(status().isCreated)
        startHand(accessTokenA, tableId).andExpect(status().isCreated)
        return SeatedContext(tableId, userIdA, accessTokenA)
    }

    // ---------- GET /api/holdem/me/seat ----------

    @Test
    fun `핸드 진행 중인 좌석의 내 좌석 조회는 200과 handInProgress true 를 응답한다`() {
        val ctx = seatedWithHandInProgress()

        authGet("/api/holdem/me/seat", ctx.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tableId").value(ctx.tableId.toInt()))
            .andExpect(jsonPath("$.handInProgress").value(true))
    }

    @Test
    fun `착석했지만 핸드가 없는 내 좌석 조회는 200과 handInProgress false 를 응답한다`() {
        val ctx = seatedWithoutHand()

        authGet("/api/holdem/me/seat", ctx.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tableId").value(ctx.tableId.toInt()))
            .andExpect(jsonPath("$.handInProgress").value(false))
    }

    @Test
    fun `미착석 사용자의 내 좌석 조회는 204를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authGet("/api/holdem/me/seat", accessToken)
            .andExpect(status().isNoContent)
    }

    // ---------- GET /api/holdem/tables ----------

    @Test
    fun `핸드 진행 중인 사용자의 테이블 목록 조회는 409와 HAND_IN_PROGRESS 를 응답한다`() {
        val ctx = seatedWithHandInProgress()

        authGet("/api/holdem/tables", ctx.accessToken)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("HAND_IN_PROGRESS"))
    }

    @Test
    fun `착석했지만 핸드가 없는 사용자의 테이블 목록 조회는 200을 응답한다`() {
        val ctx = seatedWithoutHand()

        authGet("/api/holdem/tables", ctx.accessToken)
            .andExpect(status().isOk)
    }

    @Test
    fun `미착석 사용자의 테이블 목록 조회는 200을 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authGet("/api/holdem/tables", accessToken)
            .andExpect(status().isOk)
    }

    // ---------- POST /api/holdem/tables ----------

    @Test
    fun `핸드 진행 중인 사용자가 새 테이블을 생성하면 409와 HAND_IN_PROGRESS 를 응답한다`() {
        val ctx = seatedWithHandInProgress()

        authPost("/api/holdem/tables", ctx.accessToken, """{"name":"새 테이블"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("HAND_IN_PROGRESS"))
    }

    @Test
    fun `착석했지만 핸드가 없는 사용자가 새 테이블을 생성하면 409와 ALREADY_SEATED 를 응답한다`() {
        val ctx = seatedWithoutHand()

        authPost("/api/holdem/tables", ctx.accessToken, """{"name":"새 테이블"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ALREADY_SEATED"))
    }

    @Test
    fun `미착석 사용자가 테이블을 생성하면 201을 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authPost("/api/holdem/tables", accessToken, """{"name":"새 테이블"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tableId").isNumber)
    }

    // ---------- POST /api/holdem/tables/{tableId}/seats (다른 방에 착석 시도) ----------

    @Test
    fun `핸드 진행 중인 사용자가 다른 테이블에 착석을 시도하면 409와 HAND_IN_PROGRESS 를 응답한다`() {
        val ctx = seatedWithHandInProgress()
        val (_, bystanderToken) = signUpAndLogin()
        val otherTableId = createTable(bystanderToken)

        sitDown(ctx.accessToken, otherTableId, 1, 10_000)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("HAND_IN_PROGRESS"))
    }

    @Test
    fun `착석했지만 핸드가 없는 사용자가 다른 테이블에 착석을 시도하면 409와 ALREADY_SEATED 를 응답한다`() {
        val ctx = seatedWithoutHand()
        val (_, bystanderToken) = signUpAndLogin()
        val otherTableId = createTable(bystanderToken)

        sitDown(ctx.accessToken, otherTableId, 1, 10_000)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ALREADY_SEATED"))
    }

    @Test
    fun `미착석 사용자가 테이블에 착석하면 201을 응답한다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)
        val tableId = createTable(accessToken)

        sitDown(accessToken, tableId, 1, 10_000)
            .andExpect(status().isCreated)
    }

    // ---------- DELETE /api/holdem/seat ----------

    @Test
    fun `핸드 진행 중에는 기립이 409와 HAND_IN_PROGRESS 를 응답한다`() {
        val ctx = seatedWithHandInProgress()

        authDelete("/api/holdem/seat", ctx.accessToken)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("HAND_IN_PROGRESS"))
    }

    @Test
    fun `착석했지만 핸드가 없으면 기립은 204를 응답한다`() {
        val ctx = seatedWithoutHand()

        authDelete("/api/holdem/seat", ctx.accessToken)
            .andExpect(status().isNoContent)
    }

    @Test
    fun `미착석 사용자의 기립은 404와 NOT_SEATED 를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()

        authDelete("/api/holdem/seat", accessToken)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOT_SEATED"))
    }

    // ---------- 지갑 연동 ----------

    @Test
    fun `착석하면 바이인만큼 지갑 잔액이 줄어든다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)
        val tableId = createTable(accessToken)

        sitDown(accessToken, tableId, 1, 10_000)
            .andExpect(status().isCreated)

        assertThat(balanceOf(userId)).isEqualTo(5_000L)
    }

    @Test
    fun `핸드 없이 기립하면 스택 전액이 지갑으로 돌아온다`() {
        val ctx = seatedWithoutHand(buyIn = 10_000, fundAmount = 15_000)
        assertThat(balanceOf(ctx.userId)).isEqualTo(5_000L)

        authDelete("/api/holdem/seat", ctx.accessToken)
            .andExpect(status().isNoContent)

        assertThat(balanceOf(ctx.userId)).isEqualTo(15_000L)
    }

    // ---------- POST /api/holdem/tables/{tableId}/hands ----------

    @Test
    fun `앉지 않은 사용자가 핸드 시작을 요청하면 404와 NOT_SEATED 를 응답한다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)
        val tableId = createTable(accessToken)
        sitDown(accessToken, tableId, 1, 10_000).andExpect(status().isCreated)
        val (_, bystanderToken) = signUpAndLogin()

        startHand(bystanderToken, tableId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOT_SEATED"))
    }

    // ---------- 그 외 오류 ----------

    @Test
    fun `바이인이 범위 밖이면 400과 BUY_IN_OUT_OF_RANGE 를 응답한다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)
        val tableId = createTable(accessToken)

        sitDown(accessToken, tableId, 1, 5_000)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("BUY_IN_OUT_OF_RANGE"))
    }

    @Test
    fun `이미 점유된 좌석에 착석하면 409와 SEAT_TAKEN 을 응답한다`() {
        val (userIdA, accessTokenA) = signUpAndLogin()
        val (userIdB, accessTokenB) = signUpAndLogin()
        fundWallet(userIdA, 15_000)
        fundWallet(userIdB, 15_000)
        val tableId = createTable(accessTokenA)
        sitDown(accessTokenA, tableId, 1, 10_000).andExpect(status().isCreated)

        sitDown(accessTokenB, tableId, 1, 10_000)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("SEAT_TAKEN"))
    }

    @Test
    fun `없는 테이블에 착석하면 404와 TABLE_NOT_FOUND 를 응답한다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)

        sitDown(accessToken, 999_999L, 1, 10_000)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("TABLE_NOT_FOUND"))
    }

    @Test
    fun `인증 헤더 없이 테이블 목록을 조회하면 401과 AUTHENTICATION_REQUIRED 를 응답한다`() {
        authGet("/api/holdem/tables", accessToken = null)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
    }
}
