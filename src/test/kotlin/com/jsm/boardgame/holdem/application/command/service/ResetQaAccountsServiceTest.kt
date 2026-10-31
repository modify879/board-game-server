package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ResetQaAccountsCommand
import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.port.QaAccounts
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import com.jsm.boardgame.common.error.BusinessException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class ResetQaFakeAccounts(val ids: Map<String, Long>) : QaAccounts {
    val calls = mutableListOf<String>()
    override fun userIdOf(username: String) = ids[username]
    override fun rejectPendingRequests(userId: Long, adminUserId: Long, reason: String) {
        calls += "reject:$userId:$reason"
    }
    override fun adjustBalance(userId: Long, delta: Long, adminUserId: Long, reason: String) {
        calls += "adjust:$userId:$delta"
    }
}

private class ResetQaFakeDepartures : RecentDepartures {
    val amounts = mutableMapOf<UserId, Chips>()
    override fun record(userId: UserId, amount: Chips) {
        if (amount.isZero()) amounts.remove(userId) else amounts[userId] = amount
    }
    override fun obligationOf(userId: UserId) = amounts[userId]?.let { Departure(it, Instant.MAX) }
}

private class ResetQaFakeTables(val seated: Set<UserId> = emptySet()) : HoldemTableRepository {
    override fun findById(id: TableId): HoldemTable? = null
    override fun findByUserId(userId: UserId): HoldemTable? =
        if (userId in seated) HoldemTable.create("t", TestTablePolicy) else null
    override fun findAllSeatedUserIds(): List<UserId> = seated.toList()
    override fun findAllTableIds(): List<TableId> = emptyList()
    override fun save(table: HoldemTable): HoldemTable = table
}

class ResetQaAccountsServiceTest {
    private val accounts = ResetQaFakeAccounts(mapOf("qa1" to 1L, "qa2" to 2L))
    private val departures = ResetQaFakeDepartures().apply {
        record(UserId(1L), Chips.of(5_000)); record(UserId(2L), Chips.of(5_000))
    }
    private val queue = InMemoryJoinQueue(50)
    private val balances = mutableMapOf(1L to 3_000_000L, 2L to 10_000_000L)
    private val walletBalance = object : WalletBalance {
        override fun balanceOf(userId: UserId) = balances.getValue(userId.value)
    }

    private fun service(seated: Set<UserId> = emptySet()) =
        ResetQaAccountsService(accounts, ResetQaFakeTables(seated), queue, departures, walletBalance)

    private val command = ResetQaAccountsCommand(listOf("qa1", "qa2"), adminUserId = 99L)

    @Test
    fun `의무를 지우고 대기 요청을 반려하고 잔액이 다르면 차액만큼만 조정한다`() {
        service().reset(command)

        assertNull(departures.obligationOf(UserId(1L)))
        assertNull(departures.obligationOf(UserId(2L)))
        assertEquals(
            listOf("reject:1:QA 리셋", "adjust:1:7000000", "reject:2:QA 리셋"),
            accounts.calls,
        )
    }

    @Test
    fun `잔액이 이미 기준이면 조정하지 않는다`() {
        balances[1L] = 10_000_000L
        service().reset(command)
        assertEquals(listOf("reject:1:QA 리셋", "reject:2:QA 리셋"), accounts.calls)
    }

    @Test
    fun `하나라도 착석 중이면 ALREADY_SEATED 이고 아무것도 바뀌지 않는다`() {
        val e = assertFailsWith<BusinessException> { service(seated = setOf(UserId(2L))).reset(command) }

        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
        assertEquals(emptyList(), accounts.calls)
        assertEquals(Chips.of(5_000), departures.amounts[UserId(1L)])
    }

    @Test
    fun `대기열에 있어도 ALREADY_SEATED 이고 아무것도 바뀌지 않는다`() {
        queue.enqueue(TableId(1L), UserId(1L), postBlindImmediately = false, buyIn = Chips.of(8_000))

        val e = assertFailsWith<BusinessException> { service().reset(command) }

        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
        assertEquals(emptyList(), accounts.calls)
        assertEquals(Chips.of(5_000), departures.amounts[UserId(2L)])
    }

    @Test
    fun `없는 아이디가 있으면 USER_NOT_FOUND 이고 아무것도 바뀌지 않는다`() {
        val e = assertFailsWith<BusinessException> {
            service().reset(ResetQaAccountsCommand(listOf("qa1", "nobody"), adminUserId = 99L))
        }

        assertEquals(HoldemErrorCode.USER_NOT_FOUND, e.errorCode)
        assertEquals(emptyList(), accounts.calls)
        assertEquals(Chips.of(5_000), departures.amounts[UserId(1L)])
    }
}
