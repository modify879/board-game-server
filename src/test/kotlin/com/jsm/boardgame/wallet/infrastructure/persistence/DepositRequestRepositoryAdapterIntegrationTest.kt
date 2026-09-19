package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.wallet.domain.exception.DepositRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 이중 승인 방어의 두 번째 겹(DB 낙관적 락)을 검증한다.
 * 도메인 상태 전이(같은 인스턴스를 두 번 approve 하면 막힌다)는 `DepositRequestTest` 가 이미
 * 검증했다 — 여기서 보는 것은 서로 다른 시점에 읽은 두 스냅샷이 충돌하는가, 그리고
 * `saveAndFlush` 가 그 충돌을 커밋을 기다리지 않고 즉시 터뜨리는가다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DepositRequestRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var depositRequests: DepositRequestRepository

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `같은 version 으로 두 번 저장하면 두 번째가 DepositRequestAlreadyProcessedException`() {
        val saved = depositRequests.save(DepositRequest.request(userId = System.nanoTime(), amount = Money.of(10_000), at = now))

        // 두 관리자가 동시에 같은 요청을 읽었다고 가정한다 — 둘 다 version 0 을 들고 있다.
        val firstView = requireNotNull(depositRequests.findById(saved.id!!))
        val secondView = requireNotNull(depositRequests.findById(saved.id))

        firstView.approve(1, Money.of(10_000), now)
        depositRequests.save(firstView)

        secondView.reject(2, "사유", now)

        // saveAndFlush 가 아니면 이 테스트가 실패한다: save 만 쓰면 UPDATE 의 @Version 충돌이
        // 트랜잭션 커밋 시점까지 미뤄져 어댑터의 catch 를 지나쳐 버린다.
        val e = assertFailsWith<DepositRequestAlreadyProcessedException> {
            depositRequests.save(secondView)
        }
        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, e.errorCode)
    }

    @Test
    fun `저장 후 findById 가 상태 금액 처리자 사유를 그대로 돌려준다`() {
        val request = DepositRequest.request(userId = System.nanoTime(), amount = Money.of(10_000), at = now)
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
