package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.config.TablePolicyProperties
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuyInQueryServiceTest {
    private val departures = FakeRecentDepartures()
    private val tables = object : HoldemTableRepository {
        override fun findById(id: TableId): HoldemTable? = null
        override fun findByUserId(userId: UserId): HoldemTable? = null
        override fun findAllSeatedUserIds(): List<UserId> = emptyList()
        override fun findAllTableIds(): List<TableId> = emptyList()
        override fun save(table: HoldemTable): HoldemTable = table
    }
    private val wallet = object : WalletBalance {
        override fun balanceOf(userId: UserId): Long = 10_000
    }
    private val service = BuyInQueryService(tables, departures, wallet, TablePolicyProperties(100, 200, 20, 100))

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
}
