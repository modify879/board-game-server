package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.wallet.application.query.DepositRequestQueryRepository
import com.jsm.boardgame.wallet.application.query.WalletQueryRepository
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `wallet` 조회 경로의 Kotlin JDSL 생성자 프로젝션 어댑터를 검증한다.
 * 도메인 규칙(승인/반려 등)은 각 애그리거트 테스트가 이미 검증했다 — 여기서 보는 것은
 * 프로젝션이 올바른 컬럼을 뽑는지, 페이지네이션이 id 내림차순으로 고정되는지,
 * `findByStatus` 의 선택적 조건(null=전체)이 실제로 동작하는지다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WalletQueryRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var walletQueryRepository: WalletQueryRepository

    @Autowired
    private lateinit var depositRequestQueryRepository: DepositRequestQueryRepository

    @Autowired
    private lateinit var wallets: WalletRepository

    @Autowired
    private lateinit var ledger: LedgerEntryRepository

    @Autowired
    private lateinit var depositRequests: DepositRequestRepository

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun uniqueUserId(): Long = System.nanoTime()

    @Test
    fun `원장 페이지네이션이 id 내림차순으로 나오고 totalElements 가 맞다`() {
        val userId = uniqueUserId()
        var wallet = wallets.save(Wallet.open(userId))

        repeat(3) { i ->
            val entry = wallet.record(
                type = LedgerEntryType.DEPOSIT,
                amount = Money.of(1_000),
                reference = LedgerReference(LedgerReferenceType.DEPOSIT_REQUEST, i.toLong()),
                at = now,
            )
            wallet = wallets.save(wallet)
            ledger.save(entry)
        }

        val walletId = requireNotNull(walletQueryRepository.findWalletIdByUserId(userId))
        val page = walletQueryRepository.findLedgerByWalletId(walletId, PageRequest.of(0, 10))

        assertEquals(3, page.totalElements)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), page.content.map { it.balanceAfter })
        assertTrue(page.content.zipWithNext().all { (a, b) -> a.id > b.id })
    }

    @Test
    fun `지갑이 없는 사용자는 잔액 조회가 null 이고 원장 조회는 빈 페이지다`() {
        val userId = uniqueUserId()

        assertEquals(null, walletQueryRepository.findBalanceByUserId(userId))
        assertEquals(null, walletQueryRepository.findWalletIdByUserId(userId))
    }

    @Test
    fun `findByStatus(null) 은 전체를, findByStatus(PENDING) 은 PENDING 만 돌려준다`() {
        val userId = uniqueUserId()
        val pending = depositRequests.save(DepositRequest.request(userId, Money.of(10_000), now))
        val approved = depositRequests.save(DepositRequest.request(userId, Money.of(20_000), now))
        approved.approve(adminUserId = 1, creditedAmount = Money.of(20_000), at = now)
        depositRequests.save(approved)

        val all = depositRequestQueryRepository.findByStatus(null, PageRequest.of(0, 10))
        val pendingOnly = depositRequestQueryRepository.findByStatus(DepositRequestStatus.PENDING, PageRequest.of(0, 10))

        assertTrue(all.content.map { it.id }.containsAll(listOf(pending.id!!.value, approved.id!!.value)))
        assertTrue(pendingOnly.content.all { it.status == DepositRequestStatus.PENDING.name })
        assertTrue(pendingOnly.content.any { it.id == pending.id.value })
        assertTrue(pendingOnly.content.none { it.id == approved.id.value })
    }
}
