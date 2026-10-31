package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.config.TablePolicyProperties
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TablePolicyView
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class BuyInFakeTableQuery : HoldemTableQueryRepository {
    val policies = mutableMapOf<Long, TablePolicyView>()

    override fun findPolicyOf(tableId: Long): TablePolicyView? = policies[tableId]
    override fun findSeatOf(userId: Long): SeatLocation? = throw UnsupportedOperationException()
    override fun existsHandInProgress(tableId: Long): Boolean = throw UnsupportedOperationException()
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = throw UnsupportedOperationException()
    override fun findEmptyTableIds(): List<Long> = throw UnsupportedOperationException()
}

class BuyInQueryServiceTest {
    private val departures = FakeRecentDepartures()
    private val tableQuery = BuyInFakeTableQuery()
    private val wallet = object : WalletBalance {
        override fun balanceOf(userId: UserId): Long = 10_000
    }
    private val service = BuyInQueryService(tableQuery, departures, wallet, TablePolicyProperties(100, 200, 20, 100))

    @Test
    fun `새 테이블 조회는 기본 블라인드의 20BB 에서 100BB 범위를 돌려준다`() {
        val view = service.findForNewTable(1)

        assertEquals(4_000, view.minBuyIn)
        assertEquals(20_000, view.maxBuyIn)
        assertEquals(20_000, view.maxStack)
        assertEquals(10_000, view.walletBalance)
        assertNull(view.obligationExpiresAt)
    }

    @Test
    fun `새 테이블 조회도 재착석 의무로 하한이 올라가고 만료 시각을 돌려준다`() {
        val expiresAt = Instant.parse("2030-01-01T00:00:00Z")
        departures.put(UserId(1), Departure(Chips.of(10_000), expiresAt))

        val view = service.findForNewTable(1)

        assertEquals(10_000, view.minBuyIn)
        assertEquals(20_000, view.maxBuyIn)
        assertEquals(expiresAt, view.obligationExpiresAt)
    }

    @Test
    fun `테이블 조회는 그 테이블 행의 정책으로 범위를 계산한다`() {
        tableQuery.policies[7L] = TablePolicyView(50, 100, 10, 50)

        val view = service.find(7L, 1)

        assertEquals(1_000, view.minBuyIn)
        assertEquals(5_000, view.maxBuyIn)
        assertEquals(5_000, view.maxStack)
    }

    @Test
    fun `없거나 닫힌 테이블 조회는 TABLE_NOT_FOUND 다`() {
        val e = assertFailsWith<TableNotFoundException> { service.find(7L, 1) }

        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }
}
