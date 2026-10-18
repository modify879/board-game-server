package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.NotConnectedException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.exception.InsufficientBalanceException
import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.InvalidChipsException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SitDownFakeUserConnections : UserConnections {
    private val connectedUserIds: MutableSet<Long> = mutableSetOf()

    fun connect(userId: Long) { connectedUserIds += userId }

    override fun isConnected(userId: UserId): Boolean = userId.value in connectedUserIds

    override fun hasOtherSession(userId: UserId, excludingSessionId: String): Boolean = false
}

private class SitDownFakeWalletBalance : WalletBalance {
    val balances = mutableMapOf<Long, Long>()

    override fun balanceOf(userId: UserId): Long = balances[userId.value] ?: 0
}

class SitDownServiceTest {

    private val tables = FakeHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val joinQueue = InMemoryJoinQueue(50)
    private val userConnections = SitDownFakeUserConnections()
    private val walletBalance = SitDownFakeWalletBalance().apply { balances[1L] = 8_000; balances[2L] = 8_000 }
    private val eventPublisher = ApplicationEventPublisher { }
    private val departures = FakeRecentDepartures()
    private val service = SitDownService(tables, SeatRequester(tables, handStore, joinQueue, userConnections, walletBalance, departures, eventPublisher))

    private fun createTable(): TableId = tables.save(HoldemTable.create("테스트 테이블", TestTablePolicy)).id!!

    @Test
    fun `존재하지 않는 테이블이면 TABLE_NOT_FOUND`() {
        userConnections.connect(1)

        val e = assertFailsWith<TableNotFoundException> {
            service.sitDown(SitDownCommand(tableId = 999, userId = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `이미 다른 테이블에 앉아 있으면 핸드가 없어도 ALREADY_SEATED`() {
        val otherTable = tables.save(HoldemTable.create("다른 테이블", TestTablePolicy))
        otherTable.sitDown(userId = UserId(1), buyIn = Chips.of(8_000))
        tables.save(otherTable)
        val tableId = createTable()
        userConnections.connect(1)

        val e = assertFailsWith<AlreadySeatedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `이미 앉은 테이블에서 핸드가 진행 중이면 HAND_IN_PROGRESS`() {
        val otherTable = tables.save(HoldemTable.create("다른 테이블", TestTablePolicy))
        otherTable.sitDown(userId = UserId(1), buyIn = Chips.of(8_000))
        val savedOther = tables.save(otherTable)
        handStore.save(
            savedOther.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, TestTablePolicy.smallBlind, TestTablePolicy.bigBlind, Shuffler { it }),
        )
        val tableId = createTable()
        userConnections.connect(1)

        val e = assertFailsWith<HandInProgressException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
    }

    @Test
    fun `이미 다른 테이블 대기열에 있으면 ALREADY_SEATED`() {
        val otherTableId = createTable()
        joinQueue.enqueue(otherTableId, UserId(1), false, Chips.of(8_000))
        val tableId = createTable()
        userConnections.connect(1)

        val e = assertFailsWith<AlreadySeatedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    private fun sitDownAs(buyIn: Long): Int {
        val tableId = createTable()
        userConnections.connect(1)
        return service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = buyIn))
    }

    private fun assertBuyInRejected(buyIn: Long) {
        val e = assertFailsWith<BuyInOutOfRangeException> { sitDownAs(buyIn) }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
        assertFalse(joinQueue.isQueued(UserId(1)))
    }

    @Test
    fun `바이인이 20BB 미만이면 BUY_IN_OUT_OF_RANGE`() {
        walletBalance.balances[1L] = 100_000
        assertBuyInRejected(3_800)
    }

    @Test
    fun `바이인이 100BB 를 넘으면 의무가 없을 때 BUY_IN_OUT_OF_RANGE`() {
        walletBalance.balances[1L] = 100_000
        assertBuyInRejected(20_100)
    }

    @Test
    fun `바이인 경계값 20BB 와 100BB 는 허용된다`() {
        walletBalance.balances[1L] = 100_000
        assertEquals(1, sitDownAs(4_000))
        joinQueue.removeByUserId(UserId(1))
        assertEquals(1, sitDownAs(20_000))
    }

    @Test
    fun `바이인이 칩 단위가 아니면 CHIPS_NOT_UNIT`() {
        walletBalance.balances[1L] = 100_000
        val e = assertFailsWith<InvalidChipsException> { sitDownAs(5_050) }
        assertEquals(HoldemErrorCode.CHIPS_NOT_UNIT, e.errorCode)
    }

    @Test
    fun `지갑 잔액이 바이인보다 적으면 INSUFFICIENT_BALANCE 이고 대기열에 들어가지 않는다`() {
        walletBalance.balances[1L] = 5_900
        val e = assertFailsWith<InsufficientBalanceException> { sitDownAs(6_000) }
        assertEquals(HoldemErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
        assertFalse(joinQueue.isQueued(UserId(1)))
    }

    @Test
    fun `재착석 의무가 100BB 를 넘으면 그 금액 이상으로 앉을 수 있다`() {
        walletBalance.balances[1L] = 100_000
        departures.put(UserId(1), Departure(Chips.of(30_000), Instant.MAX))
        assertEquals(1, sitDownAs(30_000))
        assertEquals(Chips.of(30_000), joinQueue.entriesOf(TableId(1)).single().buyIn)
    }

    @Test
    fun `재착석 의무보다 적게는 앉을 수 없다`() {
        walletBalance.balances[1L] = 100_000
        departures.put(UserId(1), Departure(Chips.of(15_000), Instant.MAX))
        assertBuyInRejected(14_900)
    }

    @Test
    fun `재착석 의무가 지갑 잔액보다 크면 INSUFFICIENT_BALANCE`() {
        walletBalance.balances[1L] = 9_000
        departures.put(UserId(1), Departure(Chips.of(15_000), Instant.MAX))
        assertFailsWith<InsufficientBalanceException> { sitDownAs(15_000) }
    }

    @Test
    fun `STOMP 연결이 없으면 NOT_CONNECTED 이고 대기열에 들어가지 않는다`() {
        val tableId = createTable()

        val e = assertFailsWith<NotConnectedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.NOT_CONNECTED, e.errorCode)
        assertFalse(joinQueue.isQueued(UserId(1)))
    }

    @Test
    fun `연결된 사용자가 착석하면 대기열 1번으로 들어간다`() {
        val tableId = createTable()
        userConnections.connect(1)

        val position = service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000, postBlindImmediately = true))

        assertEquals(1, position)
        val entry = joinQueue.entriesOf(tableId).single()
        assertEquals(UserId(1L), entry.userId)
        assertEquals(Chips.of(8_000), entry.buyIn)
        assertTrue(entry.postBlindImmediately)
    }

    @Test
    fun `두 번째 사용자가 이어서 착석하면 대기열 2번을 받고 FIFO 순서가 유지된다`() {
        val tableId = createTable()
        userConnections.connect(1)
        userConnections.connect(2)

        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))
        val position = service.sitDown(SitDownCommand(tableId = tableId.value, userId = 2, buyIn = 8_000))

        assertEquals(2, position)
        val entries = joinQueue.entriesOf(tableId)
        assertEquals(listOf(UserId(1L), UserId(2L)), entries.map { it.userId })
    }

    @Test
    fun `postBlindImmediately 를 생략하면 대기열 항목에도 false 로 남는다`() {
        val tableId = createTable()
        userConnections.connect(1)

        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))

        assertFalse(joinQueue.entriesOf(tableId).single().postBlindImmediately)
    }

    @Test
    fun `호출자의 트랜잭션이 롤백되면 대기열 항목도 사라지고 다시 앉을 수 있다`() {
        val tableId = createTable()
        userConnections.connect(1)

        TransactionSynchronizationManager.initSynchronization()
        try {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000, postBlindImmediately = true))
            assertTrue(joinQueue.isQueued(UserId(1)))
            TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_ROLLED_BACK,
            )
        } finally {
            TransactionSynchronizationManager.clear()
        }

        assertFalse(joinQueue.isQueued(UserId(1)))
        assertEquals(1, service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000, postBlindImmediately = true)))
    }

    @Test
    fun `호출자의 트랜잭션이 커밋되면 대기열 항목이 남는다`() {
        val tableId = createTable()
        userConnections.connect(1)

        TransactionSynchronizationManager.initSynchronization()
        try {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000, postBlindImmediately = true))
            TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_COMMITTED,
            )
        } finally {
            TransactionSynchronizationManager.clear()
        }

        assertTrue(joinQueue.isQueued(UserId(1)))
    }
}
