package com.jsm.boardgame.holdem.qa

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.model.UserId as UserDomainId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.UserId as WalletUserId
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * qa 프로필에서 덱 고정 API 로 지정한 홀카드·보드가 실제 핸드에 그대로 딜되는지 본다.
 * 타이머 값은 qa 프로필 yaml(턴 8초 등)이 아니라 테스트 JVM 의 기본(아래 properties)으로 맞춘다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.holdem.turn-timeout=1h", "app.holdem.idle-table-ttl=1h", "spring.docker.compose.enabled=false"],
)
@AutoConfigureMockMvc
@ActiveProfiles("qa")
@Import(TestcontainersConfiguration::class)
class QaDeckIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var walletRepository: WalletRepository
    @Autowired private lateinit var ledgerEntryRepository: LedgerEntryRepository
    @Autowired private lateinit var holdemTableRepository: HoldemTableRepository
    @Autowired private lateinit var handStore: HandStore
    @Autowired private lateinit var startScheduledHandUseCase: StartScheduledHandUseCase

    @LocalServerPort
    private var port: Int = 0

    private val stompClient = WebSocketStompClient(StandardWebSocketClient())

    private fun signUpAndLogin(admin: Boolean = false): Pair<Long, String> {
        val username = "u" + UniqueNames.suffix()
        val password = "password123"
        val signUp = mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password","passwordConfirm":"$password","nickname":"n${UniqueNames.suffix()}"}"""),
        ).andExpect(status().isCreated).andReturn()
        val id = signUp.response.getHeader("Location")!!.substringAfterLast("/").toLong()
        if (admin) {
            val user = userRepository.findById(UserDomainId(id))!!
            user.changeRole(UserRole.ADMIN)
            userRepository.save(user)
        }
        val login = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        return id to JsonPath.read(login.response.contentAsString, "$.accessToken")
    }

    private fun authPost(url: String, token: String, body: String): ResultActions =
        mockMvc.perform(post(url).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun fundWallet(userId: Long, amount: Long) {
        val wallet = walletRepository.findOrOpen(WalletUserId(userId))
        val entry = wallet.record(
            type = LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT,
            amount = Money.of(amount),
            reference = LedgerReference(LedgerReferenceType.ADMIN_ADJUSTMENT, 0),
            at = Instant.now(),
        )
        walletRepository.save(wallet)
        ledgerEntryRepository.save(entry)
    }

    private fun sitDownAndAwait(userId: Long, token: String, tableId: Long) {
        val headers = StompHeaders().apply { add("Authorization", "Bearer $token") }
        stompClient.connectAsync("ws://localhost:$port/ws", null, headers, object : StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS)
        authPost("/api/holdem/tables/$tableId/seats", token, """{"buyIn":10000}""").andExpect(status().isAccepted)
        val deadline = System.currentTimeMillis() + 5_000
        while (holdemTableRepository.findByUserId(UserId(userId)) == null) {
            check(System.currentTimeMillis() < deadline) { "착석이 시간 안에 끝나지 않았다: userId=$userId" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `지정한 홀카드와 보드가 다음 핸드에 그대로 딜된다`() {
        val (_, adminToken) = signUpAndLogin(admin = true)
        val players = List(3) { signUpAndLogin() }
        players.forEach { fundWallet(it.first, 10_000) }
        val tableId = holdemTableRepository.save(HoldemTable.create("qa-${UUID.randomUUID().toString().take(8)}")).id!!.value
        players.forEach { (id, token) -> sitDownAndAwait(id, token, tableId) }
        val tokenBySeat = players.associate { (id, token) -> holdemTableRepository.findByUserId(UserId(id))!!.seatOf(UserId(id))!!.seatNo to token }

        // 좌석 1 과 3 만 지정, 좌석 2 는 무작위. 보드는 5장 전부.
        authPost(
            "/api/admin/qa/holdem/tables/$tableId/next-deck",
            adminToken,
            """{"holeCards":{"1":["As","Ah"],"3":["Kc","Kd"]},"board":["Ad","7c","2h","9s","3d"]}""",
        ).andExpect(status().isNoContent)

        startScheduledHandUseCase.start(StartScheduledHandCommand(tableId))

        val hand = handStore.find(TableId(tableId))!!
        assertThat(hand.holeCardsOf(1)).containsExactly(Card.of("As"), Card.of("Ah"))
        assertThat(hand.holeCardsOf(3)).containsExactly(Card.of("Kc"), Card.of("Kd"))
        assertThat(hand.holeCardsOf(2)).doesNotContain(Card.of("As"), Card.of("Ah"), Card.of("Kc"), Card.of("Kd"))

        // 리버가 깔릴 때까지 체크/콜로 진행한다. 보드는 복원 때 다시 섞이지 않는 이미 딜된 카드이고,
        // 아직 딜 안 된 카드(턴·리버)가 지정대로인지는 실제로 깔아 봐야 알 수 있다.
        while (true) {
            val current = handStore.find(TableId(tableId))!!
            if (current.board.size == 5) {
                assertThat(current.board).containsExactly(*listOf("Ad", "7c", "2h", "9s", "3d").map(Card::of).toTypedArray())
                break
            }
            val seatNo = current.toActSeatNo!!
            val action = if (current.availableActionsFor(seatNo)?.canCheck == true) "CHECK" else "CALL"
            authPost("/api/holdem/tables/$tableId/hands/actions", tokenBySeat.getValue(seatNo), """{"action":"$action"}""")
                .andExpect(status().isNoContent)
        }
    }

    @Test
    fun `잘못된 카드 코드나 중복이나 범위 밖 좌석은 400 REQUEST_INVALID 다`() {
        val (_, adminToken) = signUpAndLogin(admin = true)
        listOf(
            """{"board":["Xx"]}""",
            """{"holeCards":{"1":["As","As"]}}""",
            """{"holeCards":{"1":["As"],"2":["As"]}}""",
            """{"holeCards":{"10":["As"]}}""",
            """{"holeCards":{"1":["As","Ah","Ad"]}}""",
            """{"board":["2c","3c","4c","5c","6c","7c"]}""",
        ).forEach { body ->
            authPost("/api/admin/qa/holdem/tables/1/next-deck", adminToken, body)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
        }
    }
}
