package com.jsm.boardgame.holdem.presentation.ws

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.infrastructure.security.config.JwtProperties
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/**
 * holdem STOMP CONNECT 인증과 SUBSCRIBE 인가 통합 테스트.
 * 좌석별 뷰(브로드캐스트 페이로드)와 타이머는 다음 조각이라 여기서 다루지 않는다 —
 * 여기는 CONNECT 관문과 구독 인가만 검증한다.
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

    @LocalServerPort
    private var port: Int = 0

    private val stompClient = WebSocketStompClient(StandardWebSocketClient())

    // ---------- REST 로 사용자/테이블/좌석을 준비하는 헬퍼 (HoldemApiIntegrationTest 와 동일한 방식) ----------

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

    private fun sitDown(accessToken: String, tableId: Long, seatNo: Int, buyIn: Long): ResultActions =
        authPost("/api/holdem/tables/$tableId/seats", accessToken, """{"seatNo":$seatNo,"buyIn":$buyIn}""")

    private data class SeatedUser(val userId: Long, val accessToken: String, val tableId: Long)

    private fun seatNewUser(buyIn: Long = 10_000L, fundAmount: Long = 15_000L): SeatedUser {
        val (userId, accessToken) = signUpAndLogin()
        fundWallet(userId, fundAmount)
        val tableId = createTable(accessToken)
        sitDown(accessToken, tableId, 1, buyIn).andExpect(status().isCreated)
        return SeatedUser(userId, accessToken, tableId)
    }

    // ---------- 만료 토큰을 직접 발급하는 헬퍼 (JwtTokenIssuer 와 같은 방식, 과거 시각으로 발급) ----------

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

    // ---------- STOMP 연결/구독 헬퍼 ----------

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

    // ---------- CONNECT 인증 ----------

    @Test
    fun `유효한 토큰으로 CONNECT 하면 연결된다`() {
        val (_, accessToken) = signUpAndLogin()

        val (session, handler) = tryConnect(accessToken)

        assertThat(session?.isConnected).isTrue()
        assertThat(handler.errorFrames.poll(1, TimeUnit.SECONDS)).isNull()
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

    // ---------- SUBSCRIBE 인가: 공개 채널 ----------

    @Test
    fun `착석한 테이블의 topic 을 구독하면 성공한다`() {
        val seated = seatNewUser()
        val (session, handler) = tryConnect(seated.accessToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.publicTopicOf(seated.tableId), noOpFrameHandler())

        assertThat(handler.errorFrames.poll(1, TimeUnit.SECONDS)).isNull()
        assertThat(session.isConnected).isTrue()
    }

    @Test
    fun `다른 테이블에 앉은 사용자가 남의 topic 을 구독하면 거부되고 ACCESS_DENIED 를 응답한다`() {
        val target = seatNewUser()
        val other = seatNewUser()
        val (session, handler) = tryConnect(other.accessToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.publicTopicOf(target.tableId), noOpFrameHandler())

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)
        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("ACCESS_DENIED")
    }

    @Test
    fun `미착석 사용자가 topic 을 구독하면 거부되고 ACCESS_DENIED 를 응답한다`() {
        val target = seatNewUser()
        val (_, unseatedAccessToken) = signUpAndLogin()
        val (session, handler) = tryConnect(unseatedAccessToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.publicTopicOf(target.tableId), noOpFrameHandler())

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)
        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("ACCESS_DENIED")
    }

    // ---------- SUBSCRIBE 인가: 개인 채널 ----------

    @Test
    fun `자기 개인 큐를 구독하면 성공한다`() {
        val seated = seatNewUser()
        val (session, handler) = tryConnect(seated.accessToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.privateQueueOf(seated.tableId), noOpFrameHandler())

        assertThat(handler.errorFrames.poll(1, TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun `남의 개인 큐를 구독하면 거부되고 ACCESS_DENIED 를 응답한다`() {
        val target = seatNewUser()
        val other = seatNewUser()
        val (session, handler) = tryConnect(other.accessToken)
        checkNotNull(session)

        session.subscribe(HoldemDestinations.privateQueueOf(target.tableId), noOpFrameHandler())

        val errorHeaders = handler.errorFrames.poll(5, TimeUnit.SECONDS)
        assertThat(errorHeaders?.getFirst("errorCode")).isEqualTo("ACCESS_DENIED")
    }
}
