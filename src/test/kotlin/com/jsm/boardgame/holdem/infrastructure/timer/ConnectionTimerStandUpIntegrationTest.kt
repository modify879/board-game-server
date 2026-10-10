package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.UniqueNames
import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.model.UserId as WalletUserId
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
 * 핸드 중 연결이 만료된 착석자의 퇴장 예약이, 핸드가 끝난 뒤 실제로 커밋되는지 검증한다.
 * [ConnectionTimer.onHandBroadcastRequested] 는 AFTER_COMMIT 콜백이라 `TableExecutor.call` 을 쓰면
 * (SerialTableExecutor 가드가 막는다) 기립이 이미 커밋된 트랜잭션에 합류해 사라진다 — post 여야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["app.holdem.next-hand-delay=1500ms"])
class ConnectionTimerStandUpIntegrationTest {

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
    private lateinit var connectionTimer: ConnectionTimer

    @LocalServerPort
    private var port: Int = 0

    private val stompClient = WebSocketStompClient(StandardWebSocketClient())

    private fun uniqueUsername(): String =
        "u" + UniqueNames.suffix()

    private fun uniqueNickname(): String =
        "n" + UniqueNames.suffix()

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

    // REST 생성은 만든 사람을 대기열에 넣는다(CreateTableService) — 이 헬퍼는 대기열이 비어 있는 테이블이 필요해
    // 저장소로 직접 만든다. accessToken 은 호출부 시그니처를 유지하려고 남겼다.
    @Suppress("UNUSED_PARAMETER")
    private fun createTable(accessToken: String, name: String = "t-${UUID.randomUUID().toString().take(8)}"): Long =
        holdemTableRepository.save(HoldemTable.create(name)).id!!.value

    /** 착석은 STOMP 연결이 있어야 대기열에 들어간다(SitDownService) — 연결부터 만든다. */
    private fun tryConnect(accessToken: String) {
        val connectHeaders = StompHeaders()
        connectHeaders.add("Authorization", "Bearer $accessToken")
        stompClient.connectAsync("ws://localhost:$port/ws", null, connectHeaders, object : StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS)
    }

    private fun sitDown(accessToken: String, tableId: Long): ResultActions {
        tryConnect(accessToken)
        return authPost("/api/holdem/tables/$tableId/seats", accessToken, """{}""")
    }

    private fun awaitSeated(userId: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (holdemTableRepository.findByUserId(UserId(userId)) != null) return
            Thread.sleep(20)
        }
        error("착석이 시간 안에 끝나지 않았습니다: userId=$userId")
    }

    private fun awaitStoodUp(userId: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (holdemTableRepository.findByUserId(UserId(userId)) == null) return
            Thread.sleep(20)
        }
        error("기립이 시간 안에 끝나지 않았습니다: userId=$userId")
    }

    private data class SeatedUser(val userId: Long, val accessToken: String)

    private fun seatNewUserAt(tableId: Long): SeatedUser {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 10_000L)
        sitDown(accessToken, tableId).andExpect(status().isAccepted)
        awaitSeated(userId)
        return SeatedUser(userId, accessToken)
    }

    private fun balanceOf(userId: Long): Long =
        walletRepository.findByUserId(WalletUserId(userId))?.balance?.amount ?: 0L

    /** 3분 대기 없이 만료를 바로 일으킨다 — private onExpire 를 현재 토큰으로 부른다(값 클래스 UserId 는 Long 으로 넘어간다). */
    private fun expireNow(userId: Long) {
        connectionTimer.beginWatch(UserId(userId))
        val tokensField = ConnectionTimer::class.java.getDeclaredField("tokens").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val token = (tokensField.get(connectionTimer) as ConcurrentHashMap<UserId, AtomicLong>)[UserId(userId)]!!.get()
        val onExpire = ConnectionTimer::class.java.declaredMethods.first { it.name.startsWith("onExpire") }
            .apply { isAccessible = true }
        onExpire.invoke(connectionTimer, userId, token)
    }

    private fun awaitCondition(message: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        error(message)
    }

    @Test
    fun `핸드 중 연결이 만료된 착석자는 핸드가 끝나면 실제로 기립해 남은 칩이 지갑에 돌아온다`() {
        val (_, hostToken) = signUpAndLogin()
        val tableId = createTable(hostToken)
        val a = seatNewUserAt(tableId)
        val b = seatNewUserAt(tableId)
        awaitCondition("핸드가 자동 시작되지 않았습니다") { handStore.find(TableId(tableId)) != null }

        // 핸드 중 a 의 연결이 만료된다 — 즉시 기립하지 않고 퇴장이 예약된다.
        expireNow(a.userId)
        assertThat(holdemTableRepository.findByUserId(UserId(a.userId))).isNotNull()
        val balanceDuringHand = balanceOf(a.userId)

        // 차례인 사람이 폴드해 핸드를 끝낸다.
        val hand = handStore.find(TableId(tableId))!!
        val toActUserId = holdemTableRepository.findById(TableId(tableId))!!.seatAt(hand.toActSeatNo!!)!!.userId.value
        val folder = listOf(a, b).first { it.userId == toActUserId }
        authPost("/api/holdem/tables/$tableId/hands/actions", folder.accessToken, """{"action":"FOLD"}""")
            .andExpect(status().isNoContent)

        awaitCondition("예약된 퇴장이 실행되지 않았습니다") { holdemTableRepository.findByUserId(UserId(a.userId)) == null }
        assertThat(balanceOf(a.userId)).isGreaterThan(balanceDuringHand)
    }
}
