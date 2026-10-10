package com.jsm.boardgame.holdem.presentation.ws

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
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
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 착석 대기열 개인 채널(/user/queue/holdem/join-queue)이 실제 STOMP 클라이언트에 SEATED 를 전달하는지. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HoldemJoinQueueStompIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var walletRepository: WalletRepository
    @Autowired private lateinit var ledgerEntryRepository: LedgerEntryRepository
    @Autowired private lateinit var holdemTableRepository: HoldemTableRepository
    @Autowired private lateinit var userRegistry: SimpUserRegistry
    @Autowired private lateinit var joinQueue: JoinQueue

    @LocalServerPort
    private var port: Int = 0

    private val stompClient = WebSocketStompClient(StandardWebSocketClient())

    private fun signUpAndLogin(): Pair<Long, String> {
        val username = "u" + UniqueNames.suffix()
        val created = mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON).content(
                """{"username":"$username","password":"password123","passwordConfirm":"password123","nickname":"n${UniqueNames.suffix()}"}""",
            ),
        ).andExpect(status().isCreated).andReturn()
        val id = created.response.getHeader("Location")!!.substringAfterLast("/").toLong()
        val login = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"password123"}"""),
        ).andExpect(status().isOk).andReturn()
        return id to JsonPath.read(login.response.contentAsString, "$.accessToken")
    }

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

    private fun connect(accessToken: String): StompSession {
        val headers = StompHeaders().apply { add("Authorization", "Bearer $accessToken") }
        return stompClient.connectAsync("ws://localhost:$port/ws", null, headers, object : StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS)
    }

    private fun joinQueueSubscriptionCount(userId: Long): Int =
        userRegistry.getUser(userId.toString())?.sessions
            ?.sumOf { s -> s.subscriptions.count { it.destination == HoldemDestinations.joinQueueDestination() } } ?: 0

    /** join-queue 를 구독하고 서버 레지스트리의 구독 수가 늘 때까지 기다린다. */
    private fun subscribeJoinQueue(session: StompSession, userId: Long): LinkedBlockingQueue<String> {
        val before = joinQueueSubscriptionCount(userId)
        val frames = LinkedBlockingQueue<String>()
        session.subscribe(HoldemDestinations.joinQueueDestination(), object : StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders): Type = ByteArray::class.java
            override fun handleFrame(headers: StompHeaders, payload: Any?) {
                frames.add(String(payload as ByteArray, Charsets.UTF_8))
            }
        })
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (joinQueueSubscriptionCount(userId) > before) return frames
            Thread.sleep(10)
        }
        error("join-queue 구독이 레지스트리에 올라오지 않았다")
    }

    private fun postAuth(url: String, token: String, body: String) = mockMvc.perform(
        post(url).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `착석 요청이 처리되면 join-queue 구독자가 SEATED 를 받는다`() {
        val (userId, token) = signUpAndLogin()
        fundWallet(userId, 10_000)
        val tableId = holdemTableRepository.save(HoldemTable.create("t-" + UUID.randomUUID().toString().take(8))).id!!.value
        val session = connect(token)
        try {
            val frames = subscribeJoinQueue(session, userId)

            postAuth("/api/holdem/tables/$tableId/seats", token, "{}").andExpect(status().isAccepted)

            val seated = frames.poll(5, TimeUnit.SECONDS)
            assertThat(seated).isNotNull()
            assertThat(JsonPath.read<String>(seated, "$.type")).isEqualTo("SEATED")
            assertThat(JsonPath.read<Number>(seated, "$.tableId").toLong()).isEqualTo(tableId)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `테이블을 만들면 만든 사람의 join-queue 구독자가 SEATED 를 받는다`() {
        val (userId, token) = signUpAndLogin()
        fundWallet(userId, 10_000)
        val session = connect(token)
        try {
            val frames = subscribeJoinQueue(session, userId)

            val result = postAuth("/api/holdem/tables", token, """{"name":"새 테이블"}""").andExpect(status().isCreated).andReturn()
            val tableId = JsonPath.read<Number>(result.response.contentAsString, "$.tableId").toLong()

            val seated = frames.poll(5, TimeUnit.SECONDS)
            assertThat(seated).isNotNull()
            assertThat(JsonPath.read<String>(seated, "$.type")).isEqualTo("SEATED")
            assertThat(JsonPath.read<Number>(seated, "$.tableId").toLong()).isEqualTo(tableId)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `이미 착석한 사용자가 구독하면 SEATED 스냅샷을 받는다`() {
        val (userId, token) = signUpAndLogin()
        fundWallet(userId, 10_000)
        // 착석이 끝난 뒤(첫 세션이 SEATED 를 받은 뒤) 새 세션으로 구독해 스냅샷만 받는다.
        val first = connect(token)
        var session: StompSession? = null
        try {
            val firstFrames = subscribeJoinQueue(first, userId)
            val result = postAuth("/api/holdem/tables", token, """{"name":"s-${UniqueNames.suffix()}"}""").andExpect(status().isCreated).andReturn()
            val tableId = JsonPath.read<Number>(result.response.contentAsString, "$.tableId").toLong()
            assertThat(firstFrames.poll(5, TimeUnit.SECONDS)).isNotNull()
            val seatNo = holdemTableRepository.findByUserId(UserId(userId))!!.seatOf(UserId(userId))!!.seatNo

            session = connect(token)
            val frames = subscribeJoinQueue(session, userId)

            val seated = frames.poll(5, TimeUnit.SECONDS)
            assertThat(seated).isNotNull()
            assertThat(JsonPath.read<String>(seated, "$.type")).isEqualTo("SEATED")
            assertThat(JsonPath.read<Number>(seated, "$.tableId").toLong()).isEqualTo(tableId)
            assertThat(JsonPath.read<Number>(seated, "$.seatNo").toInt()).isEqualTo(seatNo)
        } finally {
            session?.disconnect()
            first.disconnect()
        }
    }

    @Test
    fun `대기열에 있는 사용자가 구독하면 POSITION 스냅샷을 받는다`() {
        val (userId, token) = signUpAndLogin()
        val tableId = holdemTableRepository.save(HoldemTable.create("t-" + UUID.randomUUID().toString().take(8))).id!!
        joinQueue.enqueue(tableId, UserId(userId), false)
        val session = connect(token)
        try {
            val frames = subscribeJoinQueue(session, userId)

            val position = frames.poll(5, TimeUnit.SECONDS)
            assertThat(position).isNotNull()
            assertThat(JsonPath.read<String>(position, "$.type")).isEqualTo("POSITION")
            assertThat(JsonPath.read<Number>(position, "$.tableId").toLong()).isEqualTo(tableId.value)
            assertThat(JsonPath.read<Number>(position, "$.position").toInt()).isEqualTo(1)
        } finally {
            joinQueue.removeByUserId(UserId(userId))
            session.disconnect()
        }
    }

    @Test
    fun `착석도 대기도 아니면 구독해도 아무것도 받지 않는다`() {
        val (userId, token) = signUpAndLogin()
        val session = connect(token)
        try {
            val frames = subscribeJoinQueue(session, userId)

            assertThat(frames.poll(1, TimeUnit.SECONDS)).isNull()
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `같은 사용자의 두 번째 세션이 구독하면 스냅샷은 그 세션에만 간다`() {
        val (userId, token) = signUpAndLogin()
        val tableId = holdemTableRepository.save(HoldemTable.create("t-" + UUID.randomUUID().toString().take(8))).id!!
        val first = connect(token)
        var second: StompSession? = null
        try {
            val firstFrames = subscribeJoinQueue(first, userId)
            joinQueue.enqueue(tableId, UserId(userId), false)

            second = connect(token)
            val secondFrames = subscribeJoinQueue(second, userId)

            val position = secondFrames.poll(5, TimeUnit.SECONDS)
            assertThat(position).isNotNull()
            assertThat(JsonPath.read<String>(position, "$.type")).isEqualTo("POSITION")
            assertThat(firstFrames.poll(1, TimeUnit.SECONDS)).isNull()
        } finally {
            joinQueue.removeByUserId(UserId(userId))
            second?.disconnect()
            first.disconnect()
        }
    }
}
