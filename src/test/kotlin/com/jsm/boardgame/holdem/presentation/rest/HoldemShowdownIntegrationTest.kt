package com.jsm.boardgame.holdem.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.presentation.ws.HoldemDestinations
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 실제 HTTP API 로 핸드를 쇼다운까지 끌고 가 정산과 칩 보존을 검증하는 유일한 통합 테스트.
 * 다른 holdem 통합 테스트는 핸드 시작 직후 상태만 보거나 폴드로 조기 종료한다 — 여기가
 * 체크다운·올인 양쪽 경로로 실제 쇼다운(HandSettler.settle 의 showdownRanks/payouts 분배)까지
 * 밀어붙이는 유일한 자리다.
 *
 * 셔플은 이 클래스에서만 [ShowdownFixedShuffler] 로 고정한다 — 쇼다운 승자를 알아야 정산 금액을
 * 단언할 수 있다. `HandInProgressStoreAdapter.find()` 는 액션마다 `Hand.reconstitute` 로 남은 카드를
 * 다시 섞으므로(덱을 저장하지 않는다, 규칙 6), 한 번만 순서를 돌려주는 방식으로는 두 번째 액션부터
 * 어긋난다 — 그래서 "우선순위 카드 목록" 방식으로 구현한다: 주어진 카드 집합 중 우선순위 목록에
 * 있는 카드를 그 순서대로 앞에 두고 나머지를 뒤에 붙인다. 이러면 몇 번을 다시 섞어도(재구성해도)
 * 우선순위 카드들의 상대 순서가 항상 그대로 유지된다.
 *
 * next-hand-delay 를 1시간으로 늘려 실제 5초 타이머가 우연히 발화하지 않게 한다 — 다른 holdem
 * 통합 테스트와 같은 이유(HoldemApiIntegrationTest 참고).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["app.holdem.next-hand-delay=1h"])
class HoldemShowdownIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class ShowdownShufflerConfig {
        @Bean
        @Primary
        private fun showdownFixedShuffler(): ShowdownFixedShuffler = ShowdownFixedShuffler()
    }

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
    private lateinit var startScheduledHandUseCase: StartScheduledHandUseCase

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var fixedShuffler: ShowdownFixedShuffler

    @LocalServerPort
    private var port: Int = 0

    private val stompClient = WebSocketStompClient(StandardWebSocketClient())

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

    /** 지정한 표기의 카드를 이번 핸드가 소진할 때까지 항상 최우선으로 딜되게 고정한다. */
    private fun fixDeckOrder(vararg notations: String) {
        fixedShuffler.priorityOrder = notations.map { Card.of(it) }
    }

    /**
     * toActSeatNo 를 직접 읽어(HandStore, 도메인 애그리거트) CHECK 가 가능하면 CHECK, 아니면 CALL 로
     * 리버까지 밀어붙인다. 레이즈·폴드가 필요한 시나리오는 이 헬퍼 전에 따로 액션을 보낸다.
     */
    private fun playCheckCallToShowdown(tableId: Long, tokenBySeat: Map<Int, String>) {
        while (true) {
            val hand = handStore.find(TableId(tableId)) ?: return
            if (hand.isFinished) return
            val seatNo = hand.toActSeatNo ?: return
            val action = if (hand.availableActionsFor(seatNo)?.canCheck == true) "CHECK" else "CALL"
            authPost(
                "/api/holdem/tables/$tableId/hands/actions",
                tokenBySeat.getValue(seatNo),
                """{"action":"$action"}""",
            ).andExpect(status().isNoContent)
        }
    }

    @Test
    fun `헤즈업에서 체크다운으로 쇼다운까지 가면 정산은 스택만 바꾸고 기립 후 지갑이 칩 보존을 만족한다`() {
        val (userIdA, tokenA) = signUpAndLogin()
        val (userIdB, tokenB) = signUpAndLogin()
        fundWallet(userIdA, 15_000)
        fundWallet(userIdB, 15_000)
        val tableId = createTable(tokenA)
        sitDown(tokenA, tableId, 1, 10_000).andExpect(status().isCreated)
        sitDown(tokenB, tableId, 2, 10_000).andExpect(status().isCreated)

        // 새 테이블의 첫 헤즈업 핸드는 HoldemTable.advanceBlinds 가 결정적으로 정한다:
        // 좌석1(버튼/SB, 낮은 좌석 번호) = 포켓 에이스, 좌석2(BB) = 2-3 오프수트.
        // 딜 순서(버튼 다음 좌석부터 한 장씩 두 바퀴)는 좌석2, 좌석1, 좌석2, 좌석1 이라 홀카드 4장을
        // 이 순서로 늘어놓는다. 보드는 페어·스트레이트·플러시가 안 생기게 K-Q-J-9-8 로 고정한다.
        fixDeckOrder("2c", "As", "3d", "Ah", "Kd", "Qc", "Jh", "9s", "8h")
        startHand(tableId)

        playCheckCallToShowdown(tableId, mapOf(1 to tokenA, 2 to tokenB))

        // 정산은 테이블(스택)만 바꾼다 — 지갑은 기립 전까지 그대로다(HandSettler 는 WalletTransfer 를
        // 전혀 부르지 않는다).
        val settledTable = holdemTableRepository.findById(TableId(tableId))!!
        assertThat(settledTable.seatAt(1)?.stack?.amount).isEqualTo(10_200L)
        assertThat(settledTable.seatAt(2)?.stack?.amount).isEqualTo(9_800L)
        assertThat(balanceOf(userIdA)).isEqualTo(5_000L)
        assertThat(balanceOf(userIdB)).isEqualTo(5_000L)

        authDelete("/api/holdem/seat", tokenA).andExpect(status().isNoContent)
        authDelete("/api/holdem/seat", tokenB).andExpect(status().isNoContent)

        assertThat(balanceOf(userIdA)).isEqualTo(15_200L)
        assertThat(balanceOf(userIdB)).isEqualTo(14_800L)
        // 칩 보존 — 기립까지 끝나면 두 지갑의 합은 핸드 시작 전 두 지갑의 합과 같아야 한다.
        assertThat(balanceOf(userIdA) + balanceOf(userIdB)).isEqualTo(30_000L)
    }

    @Test
    fun `헤즈업 올인에서 숏스택이 이기면 콜하지 않은 초과분은 레이즈한 좌석에 돌아가고 칩이 보존된다`() {
        val (userIdA, tokenA) = signUpAndLogin() // 버튼-SB, 빅스택
        val (userIdB, tokenB) = signUpAndLogin() // BB, 숏스택
        fundWallet(userIdA, 25_000)
        fundWallet(userIdB, 5_000)
        val tableId = createTable(tokenA)
        sitDown(tokenA, tableId, 1, 20_000).andExpect(status().isCreated)
        sitDown(tokenB, tableId, 2, 1_000).andExpect(status().isCreated)

        // 좌석2(BB, 숏스택)가 킹 페어로 이기게, 좌석1(버튼, 빅스택)은 하이카드로 지게 고정한다.
        fixDeckOrder("Ks", "7c", "Kh", "8d", "2h", "3c", "4d", "9s", "Td")
        startHand(tableId)

        val hand = handStore.find(TableId(tableId))!!
        val shoveTo = hand.availableActionsFor(1)?.maxRaiseTo?.amount
            ?: error("좌석1의 최대 레이즈 금액을 찾을 수 없다")
        authPost(
            "/api/holdem/tables/$tableId/hands/actions",
            tokenA,
            """{"action":"RAISE_TO","raiseToAmount":$shoveTo}""",
        ).andExpect(status().isNoContent)

        // 좌석2는 자기 스택만큼만 콜할 수 있다(숏스택 올인) — 남은 라운드는 이 콜 하나로 끝난다:
        // 전원 ALL_IN 이 되어 남은 보드가 한 번에 깔리고 곧바로 쇼다운한다.
        playCheckCallToShowdown(tableId, mapOf(1 to tokenA, 2 to tokenB))

        val settledTable = holdemTableRepository.findById(TableId(tableId))!!
        // 좌석1 은 자기 올인(20,000) 중 좌석2 가 실제로 콜한 1,000 만 걸렸고 나머지 19,000 은
        // 아무도 콜하지 않아 그대로 돌아온다(Pot.layout 의 uncalledSeatNo/uncalledAmount).
        assertThat(settledTable.seatAt(1)?.stack?.amount).isEqualTo(19_000L)
        assertThat(settledTable.seatAt(2)?.stack?.amount).isEqualTo(2_000L)
        assertThat(balanceOf(userIdA)).isEqualTo(5_000L)
        assertThat(balanceOf(userIdB)).isEqualTo(4_000L)

        authDelete("/api/holdem/seat", tokenA).andExpect(status().isNoContent)
        authDelete("/api/holdem/seat", tokenB).andExpect(status().isNoContent)

        assertThat(balanceOf(userIdA)).isEqualTo(24_000L)
        assertThat(balanceOf(userIdB)).isEqualTo(6_000L)
        // 칩 보존 — 큰 스택이 순손실 1,000, 숏스택이 순이익 1,000 이고 총합은 그대로다.
        assertThat(balanceOf(userIdA) + balanceOf(userIdB)).isEqualTo(30_000L)
    }

    private fun capturingFrameHandler(): Pair<StompFrameHandler, LinkedBlockingQueue<String>> {
        val messages = LinkedBlockingQueue<String>()
        val handler = object : StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders): Type = ByteArray::class.java
            override fun handleFrame(headers: StompHeaders, payload: Any?) {
                messages.add(String(payload as ByteArray, Charsets.UTF_8))
            }
        }
        return handler to messages
    }


    private fun tryConnect(accessToken: String): StompSession {
        val connectHeaders = StompHeaders()
        connectHeaders.add("Authorization", "Bearer $accessToken")
        val future = stompClient.connectAsync(
            "ws://localhost:$port/ws",
            null,
            connectHeaders,
            object : StompSessionHandlerAdapter() {},
        )
        return future.get(5, TimeUnit.SECONDS)
    }

    /** 큐에 쌓인 메시지를 전부 비우고 마지막 메시지를 돌려준다 — 액션마다 나가는 중간 브로드캐스트를
     * 건너뛰고 핸드가 끝난 뒤의 최종 공개 뷰만 본다. */
    private fun lastMessage(queue: LinkedBlockingQueue<String>): String {
        var last = queue.poll(5, TimeUnit.SECONDS) ?: error("공개 채널 메시지를 받지 못했다")
        while (true) {
            last = queue.poll(500, TimeUnit.MILLISECONDS) ?: return last
        }
    }

    @Test
    fun `쇼다운까지 간 핸드의 공개 뷰에는 두 좌석의 패가 showdownOrder 순서로 담긴다`() {
        val (userIdA, tokenA) = signUpAndLogin()
        val (userIdB, tokenB) = signUpAndLogin()
        fundWallet(userIdA, 15_000)
        fundWallet(userIdB, 15_000)
        val tableId = createTable(tokenA)
        sitDown(tokenA, tableId, 1, 10_000).andExpect(status().isCreated)
        sitDown(tokenB, tableId, 2, 10_000).andExpect(status().isCreated)

        val session = tryConnect(tokenA)
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(tableId), publicHandler)

        // 좌석1(버튼) 포켓 에이스, 좌석2(BB) 2-3 오프수트 — 시나리오 A 와 같은 고정 덱.
        // 라운드 내내 체크/콜만 있어 레이즈가 없으므로 showdownLeaderSeatNo 는 끝까지 null 로 남고,
        // showdownOrder 는 "버튼 다음 좌석부터"(좌석2, 좌석1) 로 정해진다(Hand.showdownOrder 참고).
        fixDeckOrder("2c", "As", "3d", "Ah", "Kd", "Qc", "Jh", "9s", "8h")
        startHand(tableId)

        playCheckCallToShowdown(tableId, mapOf(1 to tokenA, 2 to tokenB))

        val finalJson = lastMessage(publicQueue)
        assertThat(JsonPath.read<Boolean>(finalJson, "$.handInProgress")).isFalse()
        val shownSeatNos = JsonPath.read<List<Int>>(finalJson, "$.result.shownHands[*].seatNo")
        assertThat(shownSeatNos).containsExactly(2, 1)
        val categories = JsonPath.read<List<String>>(finalJson, "$.result.shownHands[*].category")
        assertThat(categories).containsExactly("HIGH_CARD", "PAIR")

        session.disconnect()
    }
}

/**
 * 이 테스트에서만 셔플을 고정한다. `HandInProgressStoreAdapter.find()` 가 액션마다 남은 카드를
 * 다시 섞으므로(덱을 저장하지 않는다) 한 번 순서를 내주고 마는 방식이 아니라, 주어진 카드 집합 중
 * [priorityOrder] 에 있는 카드를 그 순서대로 앞에 두고 나머지를 뒤에 붙이는 방식으로 구현한다 —
 * 몇 번을 다시 섞어도 아직 안 딜된 우선순위 카드들의 상대 순서가 유지된다.
 */
private class ShowdownFixedShuffler : Shuffler {
    @Volatile
    var priorityOrder: List<Card> = emptyList()

    override fun shuffle(cards: List<Card>): List<Card> {
        if (priorityOrder.isEmpty()) return cards
        val prioritySet = priorityOrder.toSet()
        val prioritized = priorityOrder.filter { it in cards }
        val rest = cards.filterNot { it in prioritySet }
        return prioritized + rest
    }
}
