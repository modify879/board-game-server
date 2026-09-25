package com.jsm.boardgame.holdem.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * `holdem` 방/좌석 REST API 통합 테스트.
 * 핸드 내부 베팅 규칙 자체는 도메인 테스트가 맡는다 — 여기는 방 생성·착석·기립·조회의
 * HTTP 계약(상태 코드, errorCode)만 검증한다. 핸드는 시작 직후 상태만 확인하고 끝까지 진행하지 않는다.
 *
 * next-hand-delay 를 1시간으로 늘려 실제 5초 타이머가 테스트 도중 우연히 발화하지 않게 한다 —
 * 핸드 시작은 startHand() 헬퍼가 nextHandAt 을 과거로 강제로 당겨 StartScheduledHandUseCase 를
 * 직접 불러 결정적으로 일으킨다(수동 시작 엔드포인트는 더 이상 없다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["app.holdem.next-hand-delay=1h", "app.holdem.reveal-timeout=1h"])
class HoldemApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var walletRepository: WalletRepository

    @Autowired
    private lateinit var ledgerEntryRepository: LedgerEntryRepository

    @Autowired
    private lateinit var holdemTableRepository: HoldemTableRepository

    @Autowired
    private lateinit var startScheduledHandUseCase: StartScheduledHandUseCase

    @Autowired
    private lateinit var clock: Clock

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

    private fun balanceOf(userId: Long): Long = walletRepository.findByUserId(userId)?.balance?.amount ?: 0L

    private fun createTable(accessToken: String, name: String = "t-${UUID.randomUUID().toString().take(8)}"): Long {
        val result = authPost("/api/holdem/tables", accessToken, """{"name":"$name"}""")
            .andExpect(status().isCreated)
            .andReturn()
        return JsonPath.read<Int>(result.response.contentAsString, "$.tableId").toLong()
    }

    private fun sitDown(accessToken: String, tableId: Long, seatNo: Int, buyIn: Long): ResultActions =
        authPost("/api/holdem/tables/$tableId/seats", accessToken, """{"seatNo":$seatNo,"buyIn":$buyIn}""")

    /** 수동 시작 엔드포인트가 없으므로 nextHandAt 을 과거로 당겨 시스템 진입점을 직접 불러 결정적으로 시작시킨다. */
    private fun startHand(tableId: Long) {
        val table = holdemTableRepository.findById(TableId(tableId))!!
        table.scheduleNextHand(Instant.now(clock).minusSeconds(1))
        holdemTableRepository.save(table)
        startScheduledHandUseCase.start(StartScheduledHandCommand(tableId))
    }

    private data class SeatedContext(val tableId: Long, val userId: Long, val accessToken: String)

    private fun seatedWithoutHand(buyIn: Long = 10_000L, fundAmount: Long = 15_000L): SeatedContext {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, fundAmount)
        val tableId = createTable(accessToken)
        sitDown(accessToken, tableId, 1, buyIn).andExpect(status().isCreated)
        return SeatedContext(tableId, userId, accessToken)
    }

    /** 반환값은 착석한 둘 중 한 명(userA) 기준이다. */
    private fun seatedWithHandInProgress(): SeatedContext {
        val (userIdA, accessTokenA) = signUpAndLogin()
        val (userIdB, accessTokenB) = signUpAndLogin()
        fundWallet(userIdA, 15_000)
        fundWallet(userIdB, 15_000)
        val tableId = createTable(accessTokenA)
        sitDown(accessTokenA, tableId, 1, 10_000).andExpect(status().isCreated)
        sitDown(accessTokenB, tableId, 2, 10_000).andExpect(status().isCreated)
        startHand(tableId)
        return SeatedContext(tableId, userIdA, accessTokenA)
    }

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

    @Test
    fun `핸드 진행 중에 착석하면 202를 응답하고 지갑은 아직 불리지 않는다`() {
        val ctx = seatedWithHandInProgress()
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)

        sitDown(accessToken, ctx.tableId, 5, 10_000)
            .andExpect(status().isAccepted)

        assertThat(balanceOf(userId)).isEqualTo(15_000L)
    }

    @Test
    fun `참가 요청을 취소하면 204를 응답한다`() {
        val ctx = seatedWithHandInProgress()
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)
        sitDown(accessToken, ctx.tableId, 5, 10_000).andExpect(status().isAccepted)

        authDelete("/api/holdem/tables/${ctx.tableId}/seats/request", accessToken)
            .andExpect(status().isNoContent)
    }

    @Test
    fun `참가 요청이 없는데 취소하면 404와 JOIN_REQUEST_NOT_FOUND 를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()
        val tableId = createTable(accessToken)

        authDelete("/api/holdem/tables/$tableId/seats/request", accessToken)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("JOIN_REQUEST_NOT_FOUND"))
    }

    @Test
    fun `핸드 도중 잔액 부족한 참가 요청이 있어도 폴드로 핸드는 정산된다`() {
        val ctx = seatedWithHandInProgress()
        val (spectatorId, spectatorToken) = signUpAndLogin() // 잔액 0 — fundWallet 호출 없음
        sitDown(spectatorToken, ctx.tableId, 5, 10_000)
            .andExpect(status().isAccepted)

        authPost("/api/holdem/tables/${ctx.tableId}/hands/actions", ctx.accessToken, """{"action":"FOLD"}""")
            .andExpect(status().isNoContent)

        authGet("/api/holdem/me/seat", ctx.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.handInProgress").value(false))

        val savedTable = holdemTableRepository.findById(TableId(ctx.tableId))!!
        assertThat(savedTable.pendingJoinRequests()).isEmpty()
        assertThat(savedTable.seatAt(5)).isNull()
        assertThat(balanceOf(spectatorId)).isEqualTo(0L)
    }

    @Test
    fun `핸드 정산 뒤 잔액이 충분한 참가 요청은 좌석에 앉고 바이인만큼 지갑이 차감된다`() {
        val ctx = seatedWithHandInProgress()
        val (requesterId, requesterToken) = signUpAndLogin()
        fundWallet(requesterId, 15_000)
        sitDown(requesterToken, ctx.tableId, 5, 10_000)
            .andExpect(status().isAccepted)

        authPost("/api/holdem/tables/${ctx.tableId}/hands/actions", ctx.accessToken, """{"action":"FOLD"}""")
            .andExpect(status().isNoContent)

        authGet("/api/holdem/me/seat", ctx.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.handInProgress").value(false))

        val savedTable = holdemTableRepository.findById(TableId(ctx.tableId))!!
        assertThat(savedTable.seatAt(5)?.userId).isEqualTo(requesterId)
        assertThat(balanceOf(requesterId)).isEqualTo(5_000L)
    }

    @Test
    fun `바이인이 빅 블라인드 미만이면 400과 BUY_IN_OUT_OF_RANGE 를 응답한다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 15_000)
        val tableId = createTable(accessToken)

        sitDown(accessToken, tableId, 1, 100)
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
