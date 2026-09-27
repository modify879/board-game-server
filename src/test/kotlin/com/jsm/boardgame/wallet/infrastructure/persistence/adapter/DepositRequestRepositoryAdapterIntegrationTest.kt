package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `DepositRequestRepositoryAdapter` 가 저장한 필드를 그대로 왕복시키는지 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DepositRequestRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var depositRequests: DepositRequestRepository

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `저장 후 findById 가 상태 금액 처리자 사유를 그대로 돌려준다`() {
        val request = DepositRequest.request(userId = System.nanoTime(), amount = 10_000, at = now)
        val saved = depositRequests.save(request)

        val reloaded = requireNotNull(depositRequests.findById(saved.id!!))
        reloaded.reject(adminUserId = 7, reason = "증빙 불충분", at = now)
        depositRequests.save(reloaded)

        val final = requireNotNull(depositRequests.findById(saved.id))
        assertEquals(DepositRequestStatus.REJECTED, final.status)
        assertEquals(10_000L, final.requestedAmount.amount)
        assertEquals(7L, final.processedBy)
        assertEquals("증빙 불충분", final.rejectionReason)
    }
}
