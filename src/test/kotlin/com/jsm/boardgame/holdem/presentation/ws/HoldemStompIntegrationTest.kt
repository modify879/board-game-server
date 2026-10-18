package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.common.error.CommonErrorCode
import com.jsm.boardgame.common.web.StompSessionRegistry
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableCommand
import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.infrastructure.timer.NextHandTimer
import com.jsm.boardgame.user.infrastructure.security.config.JwtProperties
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.model.UserId as WalletUserId
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import com.nimbusds.jose.jwk.source.ImmutableSecret
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import jakarta.servlet.http.Cookie

/**
 * holdem STOMP CONNECT 인증과 SUBSCRIBE 인가 통합 테스트.
 * 타이머는 다음 조각이라 여기서 다루지 않는다 —
 * 여기는 CONNECT 관문과 구독 인가만 검증한다.
 *
 * build.gradle.kts 가 테스트 전역으로 1h 를 준다 — 실제 10초 타이머가 테스트 도중 우연히 발화하지
 * 않는다. 핸드 시작은 startHandRest() 헬퍼가 StartScheduledHandUseCase 를 직접 불러 결정적으로
 * 일으킨다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HoldemStompIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var walletRepository: WalletRepository

    @Autowired
    private lateinit var ledgerEntryRepository: LedgerEntryRepository

    @Autowired
    private lateinit var jwtProperties: JwtProperties

    @Autowired
    private lateinit var stompSessionRegistry: StompSessionRegistry

    @Autowired
    private lateinit var holdemTableRepository: HoldemTableRepository

    @Autowired
    private lateinit var startScheduledHandUseCase: StartScheduledHandUseCase

    @Autowired
    private lateinit var closeIdleTableUseCase: CloseIdleTableUseCase

    @Autowired
    private lateinit var tableExecutor: TableExecutor

    @Autowired
    private lateinit var nextHandTimer: NextHandTimer

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

    // 로그인 응답 본문의 accessToken 뿐 아니라, Set-Cookie 로 온 refresh_token 값도 함께 돌려준다 —
    // POST /api/auth/refresh 가 그 쿠키로만 리프레시 토큰을 받기 때문이다(AuthController 참고).
    private fun loginCapturingRefreshCookie(username: String, password: String): Pair<String, String> {
        val result = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        val accessToken = JsonPath.read<String>(result.response.contentAsString, "$.accessToken")
        val refreshCookie = result.response.getCookie("refresh_token")?.value
            ?: error("refresh_token 쿠키가 없다")
        return accessToken to refreshCookie
    }

    private fun signUpAndLoginCapturingRefreshCookie(): Triple<Long, String, String> {
        val username = uniqueUsername()
        val password = "password123"
        val id = idFromLocation(signUp(username, password).andExpect(status().isCreated))
        val (accessToken, refreshCookie) = loginCapturingRefreshCookie(username, password)
        return Triple(id, accessToken, refreshCookie)
    }

    /** REST 리프레시를 실제로 호출해 같은 사용자의 진짜 새 액세스 토큰을 받는다. */
    private fun refreshAccessToken(refreshCookie: String): String {
        val result = mockMvc.perform(
            post("/api/auth/refresh").cookie(Cookie("refresh_token", refreshCookie)),
        ).andExpect(status().isOk).andReturn()
        return JsonPath.read(result.response.contentAsString, "$.accessToken")
    }

    private fun authPost(url: String, accessToken: String, body: String? = null): ResultActions {
        val builder = post(url).header("Authorization", "Bearer $accessToken")
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
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

    // REST 생성은 만든 사람을 대기열에 넣는다(CreateTableService) — 이 헬퍼는 대기열이 비어 있는 테이블이 필요해
    // 저장소로 직접 만든다. accessToken 은 호출부 시그니처를 유지하려고 남겼다.
    @Suppress("UNUSED_PARAMETER")
    private fun createTable(accessToken: String, name: String = "t-${UUID.randomUUID().toString().take(8)}"): Long =
        holdemTableRepository.save(HoldemTable.create(name, TestTablePolicy)).id!!.value

    // 착석은 이제 반드시 STOMP 연결이 있어야 대기열에 들어간다(SitDownService) — 이 헬퍼가 그
    // 연결을 먼저 만든다. 반환된 세션은 쓰지 않는다(연결 자체가 목적이라 fire-and-forget).
    private fun sitDown(accessToken: String, tableId: Long): ResultActions {
        tryConnect(accessToken)
        return authPost("/api/holdem/tables/$tableId/seats", accessToken, """{"buyIn":10000}""")
    }

    private data class SeatedUser(val userId: Long, val accessToken: String, val tableId: Long)

    /** 착석 처리(ProcessJoinRequestsService)는 이제 TableExecutor.post 로 비동기 디스패치된다
     *  (JoinRequestsProcessor) — 202 응답이 돌아온 시점에 아직 안 끝났을 수 있어, 실제 착석을
     *  기다린다. 개인 큐 구독 인가는 실제로 앉아 있어야 통과하므로 이 대기가 없으면 간헐적으로
     *  구독이 조용히 버려질 수 있다. */
    private fun awaitSeated(userId: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (holdemTableRepository.findByUserId(UserId(userId)) != null) return
            Thread.sleep(20)
        }
        error("착석이 시간 안에 끝나지 않았습니다: userId=$userId")
    }

    private fun seatNewUser(fundAmount: Long = 10_000L): SeatedUser {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, fundAmount)
        val tableId = createTable(accessToken)
        sitDown(accessToken, tableId).andExpect(status().isAccepted)
        awaitSeated(userId)
        return SeatedUser(userId, accessToken, tableId)
    }

    private data class TablePair(val tableId: Long, val a: SeatedUser, val b: SeatedUser)

    private fun seatTwoUsersAtSameTable(fundAmount: Long = 10_000L): TablePair {
        val (userIdA, tokenA) = signUpAndLogin()
        fundWallet(userIdA, fundAmount)
        val tableId = createTable(tokenA)
        sitDown(tokenA, tableId).andExpect(status().isAccepted)
        awaitSeated(userIdA)

        val (userIdB, tokenB) = signUpAndLogin()
        fundWallet(userIdB, fundAmount)
        sitDown(tokenB, tableId).andExpect(status().isAccepted)
        awaitSeated(userIdB)

        return TablePair(tableId, SeatedUser(userIdA, tokenA, tableId), SeatedUser(userIdB, tokenB, tableId))
    }

    /** 시작은 시스템 트리거뿐이라 진입점을 직접 불러 결정적으로 시작시킨다. */
    private fun startHandRest(tableId: Long) {
        startScheduledHandUseCase.start(StartScheduledHandCommand(tableId))
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

    private fun expiredTokenFor(userId: Long): String {
        val secretKey = SecretKeySpec(jwtProperties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val encoder = NimbusJwtEncoder(ImmutableSecret(secretKey))
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(userId.toString())
            .claim("role", "USER")
            .issuedAt(now.minusSeconds(120))
            .expiresAt(now.minusSeconds(60))
            .build()
        return encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims),
        ).tokenValue
    }

    private class CapturingStompSessionHandler : StompSessionHandlerAdapter() {
        val errorFrames = LinkedBlockingQueue<StompHeaders>()

        override fun getPayloadType(headers: StompHeaders): Type = ByteArray::class.java

        override fun handleFrame(headers: StompHeaders, payload: Any?) {
            errorFrames.add(headers)
        }
    }

    private fun noOpFrameHandler(): StompFrameHandler = object : StompFrameHandler {
        override fun getPayloadType(headers: StompHeaders): Type = ByteArray::class.java
        override fun handleFrame(headers: StompHeaders, payload: Any?) {}
    }

    /** CONNECT 를 시도하고 (성공 시의 세션, 오류 프레임을 받는 핸들러) 를 돌려준다. */
    private fun tryConnect(accessToken: String?): Pair<StompSession?, CapturingStompSessionHandler> {
        val handler = CapturingStompSessionHandler()
        val connectHeaders = StompHeaders()
        if (accessToken != null) connectHeaders.add("Authorization", "Bearer $accessToken")

        val future = stompClient.connectAsync("ws://localhost:$port/ws", null, connectHeaders, handler)
        val session = try {
            future.get(5, TimeUnit.SECONDS)
        } catch (ex: Exception) {
            null
        }
        return session to handler
    }

    @Test
    fun `클라이언트가 0,10000 하트비트로 CONNECT 하면 서버는 10000,0 으로 응답한다`() {
        val (_, accessToken) = signUpAndLogin()
        val connected = CompletableFuture<StompHeaders>()
        val handler = object : StompSessionHandlerAdapter() {
            override fun afterConnected(session: StompSession, connectedHeaders: StompHeaders) {
                connected.complete(connectedHeaders)
            }
        }
        val connectHeaders = StompHeaders()
        connectHeaders.add("Authorization", "Bearer $accessToken")
        connectHeaders.add("heart-beat", "0,10000")

        // 클라이언트도 TaskScheduler 가 있어야 하트비트를 협상한다.
        val heartbeatClient = WebSocketStompClient(StandardWebSocketClient()).apply {
            taskScheduler = ThreadPoolTaskScheduler().apply { initialize() }
        }
        val session = heartbeatClient.connectAsync("ws://localhost:$port/ws", null, connectHeaders, handler)
            .get(5, TimeUnit.SECONDS)

        assertThat(connected.get(5, TimeUnit.SECONDS).getFirst("heart-beat")).isEqualTo("10000,0")
        session.disconnect()
    }

    @Test
    fun `유효한 토큰으로 CONNECT 하면 연결된다`() {
        val (_, accessToken) = signUpAndLogin()

        val (session, handler) = tryConnect(accessToken)

        assertThat(session?.isConnected).isTrue()
        // CONNECT 단독으로는 반드시 뒤따르는 메시지(센티널)가 없다 — isConnected 자체가 이미
        // CONNECTED 프레임 수신을 뜻하는 성공 신호다. 아래는 방어적 확인이라 대기를 짧게 둔다.
        assertThat(handler.errorFrames.poll(300, TimeUnit.MILLISECONDS)).isNull()
        session?.disconnect()
    }

    @Test
    fun `Authorization 헤더 없이 CONNECT 하면 거부되고 AUTHENTICATION_REQUIRED 를 응답한다`() {
        val (_, handler) = tryConnect(null)

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)

        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `위조된 토큰으로 CONNECT 하면 거부되고 AUTHENTICATION_REQUIRED 를 응답한다`() {
        val (_, accessToken) = signUpAndLogin()
        val forgedToken = accessToken.dropLast(4) + "abcd"

        val (_, handler) = tryConnect(forgedToken)

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)
        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `만료된 토큰으로 CONNECT 하면 거부되고 AUTHENTICATION_REQUIRED 를 응답한다`() {
        val (userId, _) = signUpAndLogin()
        val expiredToken = expiredTokenFor(userId)

        val (_, handler) = tryConnect(expiredToken)

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)
        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `착석한 테이블의 topic 을 구독하면 성공한다`() {
        val seated = seatNewUser()
        val (session, handler) = tryConnect(seated.accessToken)
        checkNotNull(session)

        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(seated.tableId), publicHandler)

        // 구독이 인가를 통과하면 HoldemSubscriptionSnapshotListener 가 공개 스냅샷을 곧바로
        // 보낸다(ERROR 는 스냅샷보다 먼저/대신 온다) — 그 수신을 센티널로 삼아 고정 대기를 없앤다.
        assertThat(publicQueue.poll(5, TimeUnit.SECONDS)).isNotNull()
        assertThat(handler.errorFrames).isEmpty()
        assertThat(session.isConnected).isTrue()
    }

    @Test
    fun `카운트다운이 도는 테이블을 구독하면 스냅샷의 nextHandInMs 가 채워져 있다`() {
        val pair = seatTwoUsersAtSameTable() // 착석 두 번째부터 카운트다운이 시작된다.

        val (session, _) = tryConnect(pair.a.accessToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandler)

        val publicJson = publicQueue.poll(5, TimeUnit.SECONDS) ?: error("공개 스냅샷을 받지 못했다")
        assertThat(JsonPath.read<Boolean>(publicJson, "$.handInProgress")).isFalse()
        val nextHandInMs = JsonPath.read<Number>(publicJson, "$.nextHandInMs").toLong()
        assertThat(nextHandInMs).isGreaterThan(0L)
    }

    @Test
    fun `핸드를 시작하면 그 핸드를 실은 공개 브로드캐스트에 turnRemainingMs 가 이미 채워져 있다`() {
        val pair = seatTwoUsersAtSameTable()
        val (session, _) = tryConnect(pair.a.accessToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandler)
        assertThat(publicQueue.poll(5, TimeUnit.SECONDS)).isNotNull() // 구독 스냅샷

        startHandRest(pair.tableId)

        // 차례 타이머 재예약이 브로드캐스트 전송보다 먼저 돌아야 첫 메시지가 예약을 읽는다.
        val publicJson = publicQueue.poll(5, TimeUnit.SECONDS) ?: error("공개 브로드캐스트를 받지 못했다")
        assertThat(JsonPath.read<Boolean>(publicJson, "$.handInProgress")).isTrue()
        val turnRemainingMs = JsonPath.read<Number>(publicJson, "$.turnRemainingMs").toLong()
        assertThat(turnRemainingMs).isBetween(1L, 60_000L)
    }

    @Test
    fun `핸드가 진행 중인 테이블을 구독하면 스냅샷의 turnRemainingMs 가 채워져 있다`() {
        val pair = seatTwoUsersAtSameTable()
        startHandRest(pair.tableId)

        val (session, _) = tryConnect(pair.a.accessToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandler)

        val publicJson = publicQueue.poll(5, TimeUnit.SECONDS) ?: error("공개 스냅샷을 받지 못했다")
        assertThat(JsonPath.read<Boolean>(publicJson, "$.handInProgress")).isTrue()
        val turnRemainingMs = JsonPath.read<Number>(publicJson, "$.turnRemainingMs").toLong()
        assertThat(turnRemainingMs).isBetween(1L, 60_000L)
    }

    @Test
    fun `다른 테이블에 앉은 사용자도 남의 topic 을 구독할 수 있다`() {
        val target = seatNewUser()
        val other = seatNewUser()
        val (session, handler) = tryConnect(other.accessToken)
        checkNotNull(session)

        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(target.tableId), publicHandler)

        assertThat(publicQueue.poll(5, TimeUnit.SECONDS)).isNotNull()
        assertThat(handler.errorFrames).isEmpty()
        assertThat(session.isConnected).isTrue()
    }

    @Test
    fun `착석하지 않은 사용자(관전자)도 topic 을 구독할 수 있다`() {
        val target = seatNewUser()
        val (_, spectatorToken) = signUpAndLogin()
        val (session, handler) = tryConnect(spectatorToken)
        checkNotNull(session)

        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(target.tableId), publicHandler)

        assertThat(publicQueue.poll(5, TimeUnit.SECONDS)).isNotNull()
        assertThat(handler.errorFrames).isEmpty()
        assertThat(session.isConnected).isTrue()
    }

    @Test
    fun `관전자의 개인 큐 구독은 조용히 무시되고 연결은 유지된다`() {
        val target = seatNewUser()
        val (_, spectatorToken) = signUpAndLogin()
        val (session, handler) = tryConnect(spectatorToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.privateQueueOf(target.tableId), noOpFrameHandler())

        assertThat(handler.errorFrames.poll(300, TimeUnit.MILLISECONDS)).isNull()
        assertThat(session.isConnected).isTrue()
        // 연결이 살아 있어 같은 테이블의 공개 토픽은 계속 구독된다.
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(target.tableId), publicHandler)
        assertThat(publicQueue.poll(5, TimeUnit.SECONDS)).isNotNull()
    }

    @Test
    fun `자기 개인 큐를 구독하면 성공한다`() {
        val seated = seatNewUser()
        val (session, handler) = tryConnect(seated.accessToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.privateQueueOf(seated.tableId), noOpFrameHandler())

        // 핸드가 없는 상태의 개인 큐 구독은 그 자체로 반드시 오는 메시지가 없다(개인 뷰는 핸드
        // 진행 중에만 나간다 — HoldemSubscriptionSnapshotListener 참고) — 센티널이 없어 대기만 줄인다.
        assertThat(handler.errorFrames.poll(300, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `대기열 개인 채널을 구독하면 성공한다`() {
        val (_, accessToken) = signUpAndLogin()
        val (session, handler) = tryConnect(accessToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.joinQueueDestination(), noOpFrameHandler())

        assertThat(handler.errorFrames.poll(300, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `남의 개인 큐 구독은 조용히 무시되고 연결은 유지된다`() {
        val target = seatNewUser()
        val other = seatNewUser()
        val (session, handler) = tryConnect(other.accessToken)
        checkNotNull(session)

        val (privateHandler, privateQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.privateQueueOf(target.tableId), privateHandler)

        assertThat(handler.errorFrames.poll(300, TimeUnit.MILLISECONDS)).isNull()
        assertThat(session.isConnected).isTrue()
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(target.tableId), publicHandler)
        assertThat(publicQueue.poll(5, TimeUnit.SECONDS)).isNotNull()
        // 공개 스냅샷까지 처리된 뒤에도 버려진 개인 구독으로는 아무것도 오지 않는다.
        assertThat(privateQueue.poll(300, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `원시 queue 목적지를 직접 구독하면 거부되고 ACCESS_DENIED 를 응답한다`() {
        val seated = seatNewUser()
        val (session, handler) = tryConnect(seated.accessToken)
        checkNotNull(session)

        session.subscribe("/queue/tables/${seated.tableId}-user${UUID.randomUUID()}", noOpFrameHandler())

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)
        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("ACCESS_DENIED")
    }

    @Test
    fun `테이블 id 가 파싱되지 않는 개인 큐 목적지를 구독하면 거부되고 ACCESS_DENIED 를 응답한다`() {
        val seated = seatNewUser()
        val (session, handler) = tryConnect(seated.accessToken)
        checkNotNull(session)

        session.subscribe("/user/queue/tables/99999999999999999999", noOpFrameHandler())

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)
        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("ACCESS_DENIED")
    }

    @Test
    fun `구독 중에 핸드를 시작하면 각자 개인 큐로 자기 홀카드만 담긴 페이로드를 받고 공개 채널 원문에는 어느 쪽 카드도 없다`() {
        val pair = seatTwoUsersAtSameTable()

        val (sessionA, _) = tryConnect(pair.a.accessToken)
        checkNotNull(sessionA)
        val (publicHandlerA, publicQueueA) = capturingFrameHandler()
        val (privateHandlerA, privateQueueA) = capturingFrameHandler()
        sessionA.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandlerA)
        sessionA.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandlerA)

        val (sessionB, _) = tryConnect(pair.b.accessToken)
        checkNotNull(sessionB)
        val (privateHandlerB, privateQueueB) = capturingFrameHandler()
        sessionB.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandlerB)

        // 구독 시점에 온 스냅샷을 비운 뒤 핸드를 시작한다. HoldemSubscriptionSnapshotListener 는
        // 목적지(topic 이든 개인 큐든)에 관계없이 구독마다 공개 채널로 한 번씩 스냅샷을 보낸다 —
        // 이 테이블에 대한 구독은 sessionA 의 topic·개인 큐(2회) + sessionB 의 개인 큐(1회) 로 총
        // 3번이라, publicQueueA(구독 중)에 정확히 3개가 쌓인다. "더 없다" 를 500ms 대기로
        // 증명하는 대신 그 개수만 받는다. 핸드가 아직 없으니 개인 큐에는 애초에 아무것도 오지
        // 않는다(개인 뷰는 핸드 진행 중에만 나간다) — 비울 것이 없다.
        // 개수를 잘못 세면 여기서 바로 실패하게 한다.
        repeat(3) { assertThat(publicQueueA.poll(3, TimeUnit.SECONDS)).isNotNull() }

        startHandRest(pair.tableId)

        val publicJson = publicQueueA.poll(5, TimeUnit.SECONDS)
        val privateJsonA = privateQueueA.poll(5, TimeUnit.SECONDS)
        val privateJsonB = privateQueueB.poll(5, TimeUnit.SECONDS)

        assertThat(publicJson).isNotNull()
        assertThat(privateJsonA).isNotNull()
        assertThat(privateJsonB).isNotNull()
        assertThat(JsonPath.read<Boolean>(publicJson, "$.handInProgress")).isTrue()

        val holeCardsA = JsonPath.read<List<String>>(privateJsonA, "$.holeCards")
        val holeCardsB = JsonPath.read<List<String>>(privateJsonB, "$.holeCards")
        assertThat(holeCardsA).hasSize(2)
        assertThat(holeCardsB).hasSize(2)
        for (card in holeCardsB) {
            // 카드 표기는 JSON 에서 항상 문자열 값으로만 나온다. 따옴표 없이 찾으면
            // toActSeatNo 같은 필드 이름의 부분 문자열에 걸려 오탐이 난다.
            assertThat(privateJsonA)
                .withFailMessage("Player B의 카드 ${card}가 Player A의 private JSON에 노출됨")
                .doesNotContain("\"$card\"")
        }
        for (card in holeCardsA) {
            assertThat(privateJsonB)
                .withFailMessage("Player A의 카드 ${card}가 Player B의 private JSON에 노출됨")
                .doesNotContain("\"$card\"")
        }
        // 규칙 6 의 계약을 원문 바이트 수준에서 재확인한다.
        for (card in holeCardsA + holeCardsB) {
            assertThat(publicJson)
                .withFailMessage("카드 ${card}가 공개 채널 원문에 노출됨")
                .doesNotContain("\"$card\"")
        }

        // 핸드 시작에 이어 액션 하나로 공개 뷰가 한 번 더 나가면, 연달아 받은 두 공개 뷰의 seq 는
        // 엄격히 증가한다 — BEFORE_COMMIT 에서 순번을 따므로 커밋 순서 = seq 순서다.
        val firstSeq = JsonPath.read<Number>(publicJson, "$.seq").toLong()
        val toActSeatNo = JsonPath.read<Int>(publicJson, "$.toActSeatNo")
        val actingToken = if (toActSeatNo == 1) pair.a.accessToken else pair.b.accessToken
        authPost("/api/holdem/tables/${pair.tableId}/hands/actions", actingToken, """{"action":"FOLD"}""")

        val secondPublicJson = publicQueueA.poll(5, TimeUnit.SECONDS)
        assertThat(secondPublicJson).isNotNull()
        val secondSeq = JsonPath.read<Number>(secondPublicJson, "$.seq").toLong()
        assertThat(secondSeq).isGreaterThan(firstSeq)

        sessionA.disconnect()
        sessionB.disconnect()
    }

    @Test
    fun `핸드가 이미 진행 중인 테이블을 구독하면 그 시점 상태를 스냅샷으로 즉시 받는다`() {
        val pair = seatTwoUsersAtSameTable()
        startHandRest(pair.tableId)

        val (sessionA, _) = tryConnect(pair.a.accessToken)
        checkNotNull(sessionA)
        val (publicHandlerA, publicQueueA) = capturingFrameHandler()
        val (privateHandlerA, privateQueueA) = capturingFrameHandler()
        sessionA.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandlerA)
        sessionA.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandlerA)

        val (sessionB, _) = tryConnect(pair.b.accessToken)
        checkNotNull(sessionB)
        val (privateHandlerB, privateQueueB) = capturingFrameHandler()
        sessionB.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandlerB)

        val publicJson = publicQueueA.poll(5, TimeUnit.SECONDS)
        val privateJsonA = privateQueueA.poll(5, TimeUnit.SECONDS)
        val privateJsonB = privateQueueB.poll(5, TimeUnit.SECONDS)

        assertThat(publicJson).isNotNull()
        assertThat(privateJsonA).isNotNull()
        assertThat(privateJsonB).isNotNull()
        assertThat(JsonPath.read<Boolean>(publicJson, "$.handInProgress")).isTrue()

        val holeCardsA = JsonPath.read<List<String>>(privateJsonA, "$.holeCards")
        val holeCardsB = JsonPath.read<List<String>>(privateJsonB, "$.holeCards")
        assertThat(holeCardsA).hasSize(2)
        assertThat(holeCardsB).hasSize(2)
        assertThat(holeCardsA).doesNotContainAnyElementsOf(holeCardsB)
        for (card in holeCardsB) {
            // 카드 표기는 JSON 에서 항상 문자열 값으로만 나온다. 따옴표 없이 찾으면
            // toActSeatNo 같은 필드 이름의 부분 문자열에 걸려 오탐이 난다.
            assertThat(privateJsonA)
                .withFailMessage("Player B의 카드 ${card}가 Player A의 private JSON에 노출됨")
                .doesNotContain("\"$card\"")
        }
        for (card in holeCardsA) {
            assertThat(privateJsonB)
                .withFailMessage("Player A의 카드 ${card}가 Player B의 private JSON에 노출됨")
                .doesNotContain("\"$card\"")
        }
        for (card in holeCardsA + holeCardsB) {
            assertThat(publicJson)
                .withFailMessage("카드 ${card}가 공개 채널 원문에 노출됨")
                .doesNotContain("\"$card\"")
        }

        sessionA.disconnect()
        sessionB.disconnect()
    }

    @Test
    fun `관전자는 구독 직후 공개 상태를 스냅샷으로 받고 그 원문에는 어느 좌석의 홀카드도 없다`() {
        val pair = seatTwoUsersAtSameTable()
        startHandRest(pair.tableId)

        val (sessionA, _) = tryConnect(pair.a.accessToken)
        checkNotNull(sessionA)
        val (privateHandlerA, privateQueueA) = capturingFrameHandler()
        sessionA.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandlerA)
        val holeCardsA = JsonPath.read<List<String>>(privateQueueA.poll(5, TimeUnit.SECONDS), "$.holeCards")

        val (sessionB, _) = tryConnect(pair.b.accessToken)
        checkNotNull(sessionB)
        val (privateHandlerB, privateQueueB) = capturingFrameHandler()
        sessionB.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandlerB)
        val holeCardsB = JsonPath.read<List<String>>(privateQueueB.poll(5, TimeUnit.SECONDS), "$.holeCards")

        // 관전자 — 어느 테이블에도 앉아 있지 않다.
        val (_, spectatorToken) = signUpAndLogin()
        val (spectatorSession, spectatorHandler) = tryConnect(spectatorToken)
        checkNotNull(spectatorSession)
        val (publicHandler, publicQueue) = capturingFrameHandler()

        spectatorSession.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandler)

        assertThat(spectatorHandler.errorFrames.poll(1, TimeUnit.SECONDS)).isNull()
        val publicJson = publicQueue.poll(5, TimeUnit.SECONDS)
        assertThat(publicJson).isNotNull()
        assertThat(JsonPath.read<Boolean>(publicJson, "$.handInProgress")).isTrue()

        for (card in holeCardsA + holeCardsB) {
            // 카드 표기는 JSON 에서 항상 따옴표를 두른 문자열 값으로만 나온다. 따옴표 없이 찾으면
            // toActSeatNo 같은 필드 이름의 부분 문자열에 걸려 오탐이 난다.
            assertThat(publicJson)
                .withFailMessage("카드 ${card}가 관전자의 공개 채널 원문에 노출됨")
                .doesNotContain("\"$card\"")
        }

        spectatorSession.disconnect()
        sessionA.disconnect()
        sessionB.disconnect()
    }

    private fun authDelete(url: String, accessToken: String): ResultActions =
        mockMvc.perform(delete(url).header("Authorization", "Bearer $accessToken"))

    /** 이미 본 seq(lastSeq) 이하의 메시지는 버리고 그보다 새로운 첫 메시지를 돌려준다. 구독마다 오는 중복 스냅샷은 같은 seq 라 걸러진다. */
    private fun nextAfter(queue: LinkedBlockingQueue<String>, lastSeq: Long): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val json = queue.poll(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: error("seq > $lastSeq 인 메시지가 시간 안에 오지 않았습니다")
            if (JsonPath.read<Number>(json, "$.seq").toLong() > lastSeq) return json
        }
    }

    private fun seqOf(json: String): Long = JsonPath.read<Number>(json, "$.seq").toLong()

    private fun leaveReservedBySeat(publicJson: String): Map<Int, Boolean> =
        JsonPath.read<List<Map<String, Any>>>(publicJson, "$.seats").associate {
            (it["seatNo"] as Number).toInt() to (it["leaveReserved"] as Boolean)
        }

    @Test
    fun `핸드 참가자의 퇴장 예약, 종류 변경, 취소가 공개 토픽과 개인 큐로 각각 실린다`() {
        val pair = seatTwoUsersAtSameTable()
        startHandRest(pair.tableId)

        val (session, _) = tryConnect(pair.a.accessToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()
        val (privateHandler, privateQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandler)
        session.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandler)

        val initialPrivate = checkNotNull(privateQueue.poll(5, TimeUnit.SECONDS))
        val initialPublic = checkNotNull(publicQueue.poll(5, TimeUnit.SECONDS))
        val mySeatNo = JsonPath.read<Int>(initialPrivate, "$.seatNo")
        assertThat(JsonPath.read<String?>(initialPrivate, "$.leaveReservation")).isNull()
        var publicSeq = seqOf(initialPublic)
        var privateSeq = seqOf(initialPrivate)

        // 1. LEAVE 예약
        authDelete("/api/holdem/seat?after=LEAVE", pair.a.accessToken).andExpect(status().isAccepted)
        var publicJson = nextAfter(publicQueue, publicSeq)
        var privateJson = nextAfter(privateQueue, privateSeq)
        publicSeq = seqOf(publicJson); privateSeq = seqOf(privateJson)
        val reserved = leaveReservedBySeat(publicJson)
        assertThat(reserved[mySeatNo]).isTrue()
        assertThat(reserved.filterKeys { it != mySeatNo }.values).isNotEmpty.containsOnly(false)
        assertThat(JsonPath.read<String>(privateJson, "$.leaveReservation")).isEqualTo("LEAVE")

        // 2. 종류 변경
        authDelete("/api/holdem/seat?after=SPECTATE", pair.a.accessToken).andExpect(status().isAccepted)
        // 종류만 바뀌어도 공개 뷰가 한 번 더 나간다 — 다음 단계에서 오래된 것을 집지 않게 같이 소비한다.
        publicJson = nextAfter(publicQueue, publicSeq)
        privateJson = nextAfter(privateQueue, privateSeq)
        publicSeq = seqOf(publicJson); privateSeq = seqOf(privateJson)
        assertThat(JsonPath.read<String>(privateJson, "$.leaveReservation")).isEqualTo("SPECTATE")

        // 3. 취소
        authDelete("/api/holdem/seat/leave-reservation", pair.a.accessToken).andExpect(status().isNoContent)
        publicJson = nextAfter(publicQueue, publicSeq)
        privateJson = nextAfter(privateQueue, privateSeq)
        assertThat(leaveReservedBySeat(publicJson).values).containsOnly(false)
        assertThat(JsonPath.read<String?>(privateJson, "$.leaveReservation")).isNull()

        session.disconnect()
    }

    @Test
    fun `예약 상태에서 개인 큐를 새로 구독하면 스냅샷에 leaveReservation 이 실려 있다`() {
        val pair = seatTwoUsersAtSameTable()
        startHandRest(pair.tableId)
        authDelete("/api/holdem/seat?after=LEAVE", pair.a.accessToken).andExpect(status().isAccepted)

        val (session, _) = tryConnect(pair.a.accessToken)
        checkNotNull(session)
        val (privateHandler, privateQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.privateQueueOf(pair.tableId), privateHandler)

        val snapshot = checkNotNull(privateQueue.poll(5, TimeUnit.SECONDS))
        assertThat(JsonPath.read<String>(snapshot, "$.leaveReservation")).isEqualTo("LEAVE")

        session.disconnect()
    }

    @Test
    fun `관전자는 공개 뷰에서 퇴장 예약 여부만 보고 예약 종류는 어디에도 없다`() {
        val pair = seatTwoUsersAtSameTable()
        startHandRest(pair.tableId)
        authDelete("/api/holdem/seat?after=LEAVE", pair.a.accessToken).andExpect(status().isAccepted)

        val (_, spectatorToken) = signUpAndLogin()
        val (session, _) = tryConnect(spectatorToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(pair.tableId), publicHandler)

        val publicJson = checkNotNull(publicQueue.poll(5, TimeUnit.SECONDS))
        assertThat(leaveReservedBySeat(publicJson).values).contains(true)
        assertThat(publicJson).doesNotContain("LEAVE").doesNotContain("SPECTATE").doesNotContain("leaveReservation")

        session.disconnect()
    }

    @Test
    fun `로그아웃하면 연결된 세션이 즉시 끊긴다`() {
        val (_, accessToken) = signUpAndLogin()
        val (session, _) = tryConnect(accessToken)
        checkNotNull(session)
        assertThat(session.isConnected).isTrue()

        authPost("/api/auth/logout", accessToken).andExpect(status().isNoContent)

        val deadline = System.currentTimeMillis() + 3_000
        while (session.isConnected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertThat(session.isConnected).isFalse()
    }

    @Test
    fun `유효한 새 액세스 토큰으로 인밴드 리프레시하면 OK 응답을 받고 세션이 유지된다`() {
        val (_, accessToken, refreshCookie) = signUpAndLoginCapturingRefreshCookie()
        val (session, handler) = tryConnect(accessToken)
        checkNotNull(session)

        // REST 리프레시로 같은 사용자의 진짜 새 액세스 토큰을 받는다 — RefreshTokenService 의
        // 30초 유예 경로도 함께 타지만, 테스트는 그 안에서 끝나므로 옛 토큰의 만료는 문제되지 않는다.
        val newAccessToken = refreshAccessToken(refreshCookie)

        val (authHandler, authQueue) = capturingFrameHandler()
        session.subscribe("/user/queue/auth", authHandler)

        val sendHeaders = StompHeaders()
        sendHeaders.destination = "/app/auth/refresh"
        sendHeaders.contentType = MediaType.APPLICATION_JSON
        session.send(sendHeaders, """{"accessToken":"$newAccessToken"}""".toByteArray(Charsets.UTF_8))

        val reply = authQueue.poll(5, TimeUnit.SECONDS)
        assertThat(reply).isNotNull()
        assertThat(JsonPath.read<String>(reply, "$.result")).isEqualTo("OK")
        assertThat(handler.errorFrames).isEmpty()
        assertThat(session.isConnected).isTrue()

        session.disconnect()
    }

    @Test
    fun `남의 유효한 토큰으로 인밴드 리프레시하면 ACCESS_DENIED 를 응답하고 세션은 유지된다`() {
        val (_, tokenA) = signUpAndLogin()
        val (_, tokenB) = signUpAndLogin()
        val (session, _) = tryConnect(tokenA)
        checkNotNull(session)

        val (authHandler, authQueue) = capturingFrameHandler()
        session.subscribe("/user/queue/auth", authHandler)

        val sendHeaders = StompHeaders()
        sendHeaders.destination = "/app/auth/refresh"
        sendHeaders.contentType = MediaType.APPLICATION_JSON
        session.send(sendHeaders, """{"accessToken":"$tokenB"}""".toByteArray(Charsets.UTF_8))

        val reply = authQueue.poll(5, TimeUnit.SECONDS)
        assertThat(reply).isNotNull()
        assertThat(JsonPath.read<String>(reply, "$.result")).isEqualTo("ERROR")
        assertThat(JsonPath.read<String>(reply, "$.errorCode")).isEqualTo(CommonErrorCode.ACCESS_DENIED.code)
        assertThat(session.isConnected).isTrue()

        session.disconnect()
    }

    @Test
    fun `STOMP 세션 id 는 WebSocketSession id 와 같아 세션 종료 처리가 소켓을 찾을 수 있다`() {
        val (_, accessToken) = signUpAndLogin()
        val (session, _) = tryConnect(accessToken)
        checkNotNull(session)

        val tokenSessionIds = stompSessionRegistry.registeredSessionIds()
        val webSocketSessionIds = stompSessionRegistry.sessionIds()

        assertThat(tokenSessionIds).isNotEmpty()
        assertThat(webSocketSessionIds).containsAll(tokenSessionIds)

        session.disconnect()
    }

    @Test
    fun `방치된 테이블을 닫으면 목록에서 사라지고 착석은 404 이며 구독자는 닫힘 알림을 받는다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 10_000)
        val tableId = createTable(accessToken)
        fun listed() = mockMvc.perform(
            get("/api/holdem/tables?size=1000").header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isOk)
        listed().andExpect(jsonPath("$.content[?(@.tableId == $tableId)]").isNotEmpty)
        val (session, _) = tryConnect(accessToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()
        session.subscribe(HoldemDestinations.publicTopicOf(tableId), publicHandler)
        assertThat(publicQueue.poll(5, TimeUnit.SECONDS)).isNotNull() // 구독 스냅샷

        nextHandTimer.restart(TableId(tableId)) // 닫힘이 이 카운트다운을 치우는지 본다(리스너 배선 확인)
        assertThat(nextHandTimer.remaining(TableId(tableId))).isNotNull()

        val closed = tableExecutor.call(TableId(tableId)) { closeIdleTableUseCase.close(CloseIdleTableCommand(tableId)) }

        assertThat(closed).isTrue()
        assertThat(nextHandTimer.remaining(TableId(tableId))).isNull()
        val notice = publicQueue.poll(5, TimeUnit.SECONDS) ?: error("닫힘 알림을 받지 못했다")
        assertThat(JsonPath.read<String>(notice, "$.type")).isEqualTo("TABLE_CLOSED")
        assertThat(JsonPath.read<Int>(notice, "$.tableId").toLong()).isEqualTo(tableId)
        listed().andExpect(jsonPath("$.content[?(@.tableId == $tableId)]").isEmpty)
        authPost("/api/holdem/tables/$tableId/seats", accessToken, """{"buyIn":10000}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("TABLE_NOT_FOUND"))
    }

    @Test
    fun `닫힌 테이블을 새로 구독하면 닫힘 알림을 다시 받는다`() {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, 10_000)
        val tableId = createTable(accessToken)
        tableExecutor.call(TableId(tableId)) { closeIdleTableUseCase.close(CloseIdleTableCommand(tableId)) }
        val (session, _) = tryConnect(accessToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()

        session.subscribe(HoldemDestinations.publicTopicOf(tableId), publicHandler)

        val notice = publicQueue.poll(5, TimeUnit.SECONDS) ?: error("닫힘 알림을 받지 못했다")
        assertThat(JsonPath.read<String>(notice, "$.type")).isEqualTo("TABLE_CLOSED")
        session.disconnect()
    }

    @Test
    fun `존재한 적 없는 테이블을 구독해도 닫힘 알림을 받는다`() {
        val (_, accessToken) = signUpAndLogin()
        val (session, _) = tryConnect(accessToken)
        checkNotNull(session)
        val (publicHandler, publicQueue) = capturingFrameHandler()

        session.subscribe(HoldemDestinations.publicTopicOf(999_999_999L), publicHandler)

        val notice = publicQueue.poll(5, TimeUnit.SECONDS) ?: error("닫힘 알림을 받지 못했다")
        assertThat(JsonPath.read<String>(notice, "$.type")).isEqualTo("TABLE_CLOSED")
        session.disconnect()
    }
}
