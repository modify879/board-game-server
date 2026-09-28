package com.jsm.boardgame.holdem.infrastructure.timer

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.Hand
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
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 다음 핸드 자동 시작 타이머 재현 테스트. 실제 스프링 배선(TableExecutor/NextHandTimer/TurnTimer)을
 * 그대로 쓴다 — `app.holdem.next-hand-delay` 만 짧게 줄여 실제 타이머가 초 단위로 발화하게 한다.
 *
 * 재현 대상 버그(과거): 헤즈업에서 한쪽이 시간 초과로 폴드·즉시 기립되면 후보가 1명뿐이라 자동
 * 시작을 건너뛴다. 그 뒤 남은 사용자가 다시 앉으면 카운트다운이 다시 걸려야 하는데, 예전에는
 * DB(`next_hand_at`) 값을 벽시계와 재비교하는 가드가 있어, WSL2 처럼 벽시계가 뒤로 튀는 환경에서
 * 그 가드가 조용히 실패해 다음 핸드가 영영 시작되지 않는 사례가 보고됐다. 지금은 `NextHandCountdown`
 * 이 상대 시간(단조 시계)만 쓰고 발화 후 벽시계로 다시 비교하지 않는다 — 이 클래스는 그 배선이
 * 실제로 동작하는지를 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["app.holdem.next-hand-delay=1500ms"])
class NextHandTimerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var walletRepository: WalletRepository

    @Autowired
    private lateinit var ledgerEntryRepository: LedgerEntryRepository

    @Autowired
    private lateinit var holdemTableRepository: HoldemTableRepository

    @Autowired
    private lateinit var handStore: HandStore

    @Autowired
    private lateinit var tableExecutor: TableExecutor

    @Autowired
    private lateinit var expireTurnUseCase: ExpireTurnUseCase

    @Autowired
    private lateinit var nextHandTimer: NextHandTimer

    @LocalServerPort
    private var port: Int = 0

    private val stompClient = WebSocketStompClient(StandardWebSocketClient())

    // ---- 회원가입/로그인/지갑/착석 헬퍼 (HoldemStompIntegrationTest 와 같은 패턴) ----

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

    private fun createTable(accessToken: String, name: String = "t-${UUID.randomUUID().toString().take(8)}"): Long {
        val result = authPost("/api/holdem/tables", accessToken, """{"name":"$name"}""")
            .andExpect(status().isCreated)
            .andReturn()
        return JsonPath.read<Int>(result.response.contentAsString, "$.tableId").toLong()
    }

    /** 착석은 STOMP 연결이 있어야 대기열에 들어간다(SitDownService) — 연결부터 만든다. */
    private fun tryConnect(accessToken: String) {
        val connectHeaders = StompHeaders()
        connectHeaders.add("Authorization", "Bearer $accessToken")
        stompClient.connectAsync("ws://localhost:$port/ws", null, connectHeaders, object : StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS)
    }

    private fun sitDown(accessToken: String, tableId: Long, buyIn: Long = 10_000L): ResultActions {
        tryConnect(accessToken)
        return authPost("/api/holdem/tables/$tableId/seats", accessToken, """{"buyIn":$buyIn}""")
    }

    private fun awaitSeated(userId: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (holdemTableRepository.findByUserId(userId) != null) return
            Thread.sleep(20)
        }
        error("착석이 시간 안에 끝나지 않았습니다: userId=$userId")
    }

    private fun awaitStoodUp(userId: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (holdemTableRepository.findByUserId(userId) == null) return
            Thread.sleep(20)
        }
        error("기립이 시간 안에 끝나지 않았습니다: userId=$userId")
    }

    private data class SeatedUser(val userId: Long, val accessToken: String)

    private fun seatNewUserAt(tableId: Long, buyIn: Long = 10_000L, fundAmount: Long = 15_000L): SeatedUser {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, fundAmount)
        sitDown(accessToken, tableId, buyIn).andExpect(status().isAccepted)
        awaitSeated(userId)
        return SeatedUser(userId, accessToken)
    }

    // ---- 폴링 ----

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
    }

    private fun awaitHandInProgress(tableId: Long, timeoutMs: Long = 3_000): Hand {
        pollUntil(timeoutMs) { handStore.find(TableId(tableId)) != null }
        return handStore.find(TableId(tableId))
            ?: error("자동 시작이 ${timeoutMs}ms 안에 일어나지 않았습니다.\n${diagnose(tableId)}")
    }

    private fun awaitNoHandInProgress(tableId: Long, timeoutMs: Long = 3_000) {
        pollUntil(timeoutMs) { handStore.find(TableId(tableId)) == null }
        if (handStore.find(TableId(tableId)) != null) {
            error("핸드 정산이 ${timeoutMs}ms 안에 끝나지 않았습니다.\n${diagnose(tableId)}")
        }
    }

    private fun awaitCountdownCancelled(tableId: Long, timeoutMs: Long = 3_000) {
        pollUntil(timeoutMs) { nextHandTimer.remaining(TableId(tableId)) == null }
        if (nextHandTimer.remaining(TableId(tableId)) != null) {
            error("카운트다운이 ${timeoutMs}ms 안에 취소되지 않았습니다.\n${diagnose(tableId)}")
        }
    }

    // ---- NextHandTimer 내부 상태 진단(리플렉션, 읽기 전용) ----

    private fun diagnose(tableId: Long): String {
        val tid = TableId(tableId)
        return try {
            val scheduledField = NextHandTimer::class.java.getDeclaredField("scheduled").apply { isAccessible = true }
            val tokensField = NextHandTimer::class.java.getDeclaredField("tokens").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val scheduledMap = scheduledField.get(nextHandTimer) as ConcurrentHashMap<TableId, Any>
            @Suppress("UNCHECKED_CAST")
            val tokensMap = tokensField.get(nextHandTimer) as ConcurrentHashMap<TableId, AtomicLong>

            val scheduledEntry = scheduledMap[tid]
            val scheduledDesc = if (scheduledEntry != null) {
                val cls = scheduledEntry.javaClass
                val futureField = cls.getDeclaredField("future").apply { isAccessible = true }
                val tokenField = cls.getDeclaredField("token").apply { isAccessible = true }
                val future = futureField.get(scheduledEntry) as ScheduledFuture<*>
                val token = tokenField.get(scheduledEntry) as Long
                "ScheduledStart(token=$token, isDone=${future.isDone}, isCancelled=${future.isCancelled}, " +
                    "delay=${future.getDelay(TimeUnit.MILLISECONDS)}ms)"
            } else {
                "null"
            }
            val currentToken = tokensMap[tid]?.get()
            val table = holdemTableRepository.findById(tid)

            """
            |now=${Instant.now()}
            |table.candidateSeatNos=${table?.candidateSeatNos()}
            |table.occupiedSeats=${table?.occupiedSeats()?.map { it.seatNo to it.userId }}
            |handStore.find=${handStore.find(tid)}
            |NextHandTimer.scheduled[$tid]=$scheduledDesc
            |NextHandTimer.tokens[$tid]=$currentToken
            """.trimMargin()
        } catch (e: Exception) {
            "진단 수집 실패: ${e}"
        }
    }

    // ---- (a) 기준선: 둘이 앉으면 타이머로 핸드가 자동 시작된다 ----

    @Test
    fun `두 사용자가 앉으면 타이머로 핸드가 3초 안에 자동 시작된다`() {
        val (_, hostToken) = signUpAndLogin()
        val tableId = createTable(hostToken)
        val a = seatNewUserAt(tableId)
        val b = seatNewUserAt(tableId)

        val hand = awaitHandInProgress(tableId)

        assertThat(hand.toActSeatNo).isNotNull()
        assertThat(holdemTableRepository.findById(TableId(tableId))!!.occupiedSeats()).hasSize(2)
        assertThat(listOf(a.userId, b.userId)).isNotEmpty()
    }

    // ---- (b) 재현: 시간 초과 폴드 → 후보 부족으로 자동 시작 건너뜀 → 재입장 → 자동 시작돼야 한다 ----

    @Test
    fun `시간 초과 폴드로 후보가 부족해 자동 시작을 건너뛴 뒤 남은 사용자가 재입장하면 3초 안에 다음 핸드가 자동 시작된다`() {
        val (_, hostToken) = signUpAndLogin()
        val tableId = createTable(hostToken)
        val a = seatNewUserAt(tableId)
        val b = seatNewUserAt(tableId)

        // 1) 핸드가 자동 시작될 때까지 기다린다.
        val hand = awaitHandInProgress(tableId)
        val toActSeatNo = hand.toActSeatNo ?: error("toActSeatNo 가 없다")
        val folderUserId = holdemTableRepository.findById(TableId(tableId))!!.seatAt(toActSeatNo)!!.userId
        val folder = listOf(a, b).first { it.userId == folderUserId }

        // TurnTimer 가 하는 것과 똑같이, 그 테이블의 실행기를 거쳐 시간 초과 처리를 직접 건다.
        tableExecutor.call(TableId(tableId)) { expireTurnUseCase.expire(ExpireTurnCommand(tableId, toActSeatNo)) }

        // 2) 헤즈업이 끝나 즉시 정산되고(HandSettler 가 후보가 있으면 무조건 카운트다운을 새로
        //    건다), 폴드한 사용자는 즉시 기립한다.
        awaitNoHandInProgress(tableId)
        awaitStoodUp(folderUserId)
        assertThat(holdemTableRepository.findById(TableId(tableId))!!.occupiedSeats()).hasSize(1)

        // 3) NextHandTimer 가 발화하지만 후보가 1명뿐이라 StartScheduledHandService 가 조용히
        //    끝난다 — 카운트다운은 이미 소진돼 남아 있지 않다.
        awaitCountdownCancelled(tableId)

        // 4) 기립했던 사용자가 다시 앉는다(HandStarter.rescheduleOnEntry 가 카운트다운을 다시
        //    걸어야 한다).
        sitDown(folder.accessToken, tableId).andExpect(status().isAccepted)
        awaitSeated(folderUserId)

        // 5) 다음 핸드가 3초 안에 자동 시작돼야 한다 — 실패하면 NextHandTimer 내부 상태를 진단으로 남긴다.
        awaitHandInProgress(tableId)
    }

    // ---- (c) 변형: 카운트다운 도중 한 명이 기립하고, 다음 사용자가 재입장한다 ----

    @Test
    fun `카운트다운 도중 한 명이 기립하고 다음 사용자가 앉으면 3초 안에 자동 시작된다`() {
        val (_, hostToken) = signUpAndLogin()
        val tableId = createTable(hostToken)
        val a = seatNewUserAt(tableId)
        val b = seatNewUserAt(tableId) // 이 시점부터 카운트다운이 시작된다.

        // 카운트다운이 아직 발화하기 전에(핸드가 없는 상태에서만 기립 가능) a 를 기립시킨다.
        authDelete("/api/holdem/seat", a.accessToken).andExpect(status().isNoContent)
        awaitStoodUp(a.userId)

        // 후보가 1명(b)으로 줄어 rescheduleOnExit 가 카운트다운을 취소했어야 한다.
        awaitCountdownCancelled(tableId)
        awaitNoHandInProgress(tableId, timeoutMs = 500)

        // 다음 사용자 c 가 앉아 후보를 다시 2명으로 만든다 — rescheduleOnEntry 가 새로 예약해야 한다.
        val c = seatNewUserAt(tableId)

        awaitHandInProgress(tableId)
        assertThat(listOf(b.userId, c.userId)).isNotEmpty()
    }

    // ---- (d) 사용자 결정: 카운트다운 도중 새 참가자가 들어오면 리셋된다(이어지지 않는다) ----

    @Test
    fun `카운트다운 도중 세 번째 사용자가 앉으면 그 입장 시각부터 전체 지연만큼 다시 카운트다운된다`() {
        val (_, hostToken) = signUpAndLogin()
        val tableId = createTable(hostToken)
        val a = seatNewUserAt(tableId)
        val b = seatNewUserAt(tableId) // 이 시점부터 1500ms 카운트다운이 시작된다.

        // 지연의 약 40% 가 지난 시점에 세 번째 사용자가 앉는다 — 아직 카운트다운이 끝나기 전이다.
        Thread.sleep(600)
        val beforeEntry = System.nanoTime()
        val c = seatNewUserAt(tableId)

        // 리셋됐다면 남은 시간은 거의 전체 지연(1500ms)에 가까워야 한다 — 리셋되지 않았다면
        // 900ms 근처(1500 - 600)에 머물러 있을 것이다.
        val remainingJustAfterEntry = nextHandTimer.remaining(TableId(tableId))
            ?: error("착석 직후 카운트다운이 없다.\n${diagnose(tableId)}")
        assertThat(remainingJustAfterEntry.toMillis()).isGreaterThan(1_200L)

        // 원래(리셋 없었다면) 마감이었을 시각 + 여유를 살짝 지난 시점에도 아직 핸드가 시작되지
        // 않아야 한다 — 리셋되지 않았다면 이 시점에 이미 시작돼 있을 것이다.
        Thread.sleep(500) // beforeEntry 기준 약 900ms 경과 — 리셋 없었다면 마감을 이미 지났다
        assertThat(handStore.find(TableId(tableId))).isNull()

        // 리셋된 마감(= 세 번째 사용자 입장 + 전체 지연)에서는 정상적으로 시작된다.
        awaitHandInProgress(tableId)
        val elapsedFromEntryMs = (System.nanoTime() - beforeEntry) / 1_000_000
        assertThat(elapsedFromEntryMs).isGreaterThan(1_200L) // 리셋되지 않았다면 900ms 근처에서 이미 시작됐을 것이다
        assertThat(listOf(a.userId, b.userId, c.userId)).isNotEmpty()
    }
}
