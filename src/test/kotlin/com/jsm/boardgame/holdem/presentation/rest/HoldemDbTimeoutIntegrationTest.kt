package com.jsm.boardgame.holdem.presentation.rest

import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * DB 타임아웃(application.yaml)이 실제로 걸리고, 지갑 락 대기가 테이블 스레드를 붙잡지 않으며
 * 재시도 가능한 503 으로 보이는지 확인한다. 지갑을 잠그는 동기 REST 경로로 기립(캐시아웃)을 쓴다 —
 * 착석은 대기열을 거쳐 비동기라 REST 가 오류를 못 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HoldemDbTimeoutIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var walletRepository: WalletRepository
    @Autowired private lateinit var ledgerEntryRepository: LedgerEntryRepository
    @Autowired private lateinit var holdemTableRepository: HoldemTableRepository

    @LocalServerPort
    private var port: Int = 0

    private val stompClient = WebSocketStompClient(StandardWebSocketClient())

    @Test
    fun `커넥션에 lock_timeout 과 statement_timeout 이 적용된다`() {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String::class.java)).isEqualTo("3s")
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String::class.java)).isEqualTo("10s")
    }

    @Test
    fun `지갑 락을 누가 쥐고 있으면 기립은 503 으로 실패하고 락을 풀면 같은 테이블에서 성공한다`() {
        val suffix = UniqueNames.suffix()
        val username = "u$suffix"
        val userId = signUp(username, suffix)
        val token = login(username)
        fundWallet(userId, 10_000)
        // REST 생성은 만든 사람을 대기열에 넣는다 — 착석 요청을 직접 하려고 저장소로 만든다.
        val tableId = holdemTableRepository.save(HoldemTable.create("t-$suffix", TestTablePolicy)).id!!.value
        connectStomp(token)
        authPost("/api/holdem/tables/$tableId/seats", token, """{"buyIn":10000}""").andExpect(status().isAccepted)
        awaitSeated(userId)

        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            transactionTemplate.executeWithoutResult {
                jdbc.queryForList("SELECT id FROM wallets WHERE user_id = ? FOR UPDATE", userId)
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue()

            authDelete("/api/holdem/seat", token)
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.errorCode").value("TEMPORARILY_UNAVAILABLE"))
        } finally {
            release.countDown()
            holder.join()
        }

        authDelete("/api/holdem/seat", token).andExpect(status().isNoContent)
    }

    private fun signUp(username: String, nickSuffix: String): Long {
        val result = mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON).content(
                """{"username":"$username","password":"password123","passwordConfirm":"password123","nickname":"n${nickSuffix}"}""",
            ),
        ).andExpect(status().isCreated).andReturn()
        return result.response.getHeader("Location")!!.substringAfterLast("/").toLong()
    }

    private fun login(username: String): String {
        val result = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"password123"}"""),
        ).andExpect(status().isOk).andReturn()
        return JsonPath.read(result.response.contentAsString, "$.accessToken")
    }

    private fun authPost(url: String, token: String, body: String): ResultActions =
        mockMvc.perform(post(url).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun authDelete(url: String, token: String): ResultActions =
        mockMvc.perform(delete(url).header("Authorization", "Bearer $token"))

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

    private fun connectStomp(token: String) {
        val headers = StompHeaders()
        headers.add("Authorization", "Bearer $token")
        stompClient.connectAsync("ws://localhost:$port/ws", null, headers, object : StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS)
    }

    private fun awaitSeated(userId: Long) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (holdemTableRepository.findByUserId(UserId(userId)) != null) return
            Thread.sleep(20)
        }
        error("착석이 시간 안에 끝나지 않았습니다: userId=$userId")
    }
}
