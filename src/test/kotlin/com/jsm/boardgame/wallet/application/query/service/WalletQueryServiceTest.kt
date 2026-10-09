package com.jsm.boardgame.wallet.application.query.service

import com.jsm.boardgame.wallet.application.query.port.WalletQueryRepository
import com.jsm.boardgame.wallet.application.query.view.LedgerEntryView
import com.jsm.boardgame.wallet.application.query.view.WalletBalanceView
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class WalletQueryFakeRepository : WalletQueryRepository {
    var walletId: Long? = null
    var balance: WalletBalanceView? = null
    var ledgerCalls = 0
    val ledger = listOf(LedgerEntryView(1L, "DEPOSIT", 100, 100, "DEPOSIT_REQUEST", 7L, null, Instant.EPOCH))

    override fun findBalanceByUserId(userId: Long): WalletBalanceView? = balance
    override fun findWalletIdByUserId(userId: Long): Long? = walletId
    override fun findLedgerByWalletId(walletId: Long, pageable: Pageable): Page<LedgerEntryView> {
        ledgerCalls++
        return PageImpl(ledger, pageable, ledger.size.toLong())
    }
}

class WalletQueryServiceTest {

    private val repo = WalletQueryFakeRepository()
    private val service = WalletQueryService(repo)
    private val pageable = PageRequest.of(0, 10)

    @Test
    fun `지갑이 없으면 잔액 0 을 돌려준다`() {
        assertEquals(0L, service.findBalance(1L).balance)
    }

    @Test
    fun `지갑이 있으면 그 잔액을 돌려준다`() {
        repo.balance = WalletBalanceView(500)

        assertEquals(500L, service.findBalance(1L).balance)
    }

    @Test
    fun `지갑이 없으면 원장은 빈 페이지이고 원장 조회를 하지 않는다`() {
        val page = service.findLedger(1L, pageable)

        assertTrue(page.isEmpty)
        assertEquals(pageable, page.pageable)
        assertEquals(0, repo.ledgerCalls)
    }

    @Test
    fun `지갑이 있으면 원장을 돌려준다`() {
        repo.walletId = 5L

        val page = service.findLedger(1L, pageable)

        assertEquals(1, page.totalElements)
        assertEquals(1, repo.ledgerCalls)
    }
}
