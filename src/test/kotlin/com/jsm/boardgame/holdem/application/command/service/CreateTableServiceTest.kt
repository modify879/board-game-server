package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.application.config.TablePolicyProperties
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.command.usecase.CreateTableCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.NotConnectedException
import com.jsm.boardgame.holdem.application.exception.InsufficientBalanceException
import com.jsm.boardgame.holdem.application.port.FakeRecentDepartures
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class CreateTableFakeUserConnections : UserConnections {
    val connected = mutableSetOf<UserId>()

    override fun isConnected(userId: UserId): Boolean = userId in connected

    override fun hasOtherSession(userId: UserId, excludingSessionId: String): Boolean = false
}

private class CreateTableFakeWalletBalance : WalletBalance {
    val balances = mutableMapOf<UserId, Long>()

    override fun balanceOf(userId: UserId): Long = balances[userId] ?: 0
}

class CreateTableServiceTest {

    private val tables = FakeHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val joinQueue = InMemoryJoinQueue(50)
    private val userConnections = CreateTableFakeUserConnections().apply { connected += UserId(1L) }
    private val walletBalance = CreateTableFakeWalletBalance().apply { balances[UserId(1L)] = 8_000 }
    private val service = CreateTableService(
        tables,
        SeatRequester(tables, handStore, joinQueue, userConnections, walletBalance, FakeRecentDepartures(), ApplicationEventPublisher { }),
        TablePolicyProperties(100, 200, 20, 100),
    )

    private fun seatedTable(userId: UserId): HoldemTable {
        val table = HoldemTable.create("기존 테이블", TestTablePolicy)
        table.sitDown(userId, Chips.of(8_000))
        return tables.save(table)
    }

    @Test
    fun `이름을 주면 테이블이 생성되고 id 가 발급된다`() {
        val id = service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블", buyIn = 8_000))

        assertNotNull(tables.findById(id))
        assertEquals("새 테이블", tables.findById(id)!!.name)
    }

    @Test
    fun `이미 다른 테이블에 앉아 있으면 핸드가 없어도 ALREADY_SEATED`() {
        seatedTable(userId = UserId(1L))

        val e = assertFailsWith<AlreadySeatedException> {
            service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블", buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `이미 앉은 테이블에서 핸드가 진행 중이면 HAND_IN_PROGRESS`() {
        val table = seatedTable(userId = UserId(1L))
        handStore.save(
            table.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, TestTablePolicy.smallBlind, TestTablePolicy.bigBlind, Shuffler { it }),
        )

        val e = assertFailsWith<HandInProgressException> {
            service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블", buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
    }

    @Test
    fun `만든 사람은 새 테이블의 대기열에 들어간다`() {
        val id = service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블", buyIn = 8_000))

        assertEquals(id, joinQueue.tableOf(UserId(1L)))
    }

    @Test
    fun `지갑 잔액이 바이인보다 적으면 INSUFFICIENT_BALANCE 이고 대기열에 남지 않는다`() {
        walletBalance.balances[UserId(1L)] = 100

        val e = assertFailsWith<InsufficientBalanceException> {
            service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블", buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
        assertTrue(!joinQueue.isQueued(UserId(1L)))
    }

    @Test
    fun `바이인이 20BB 미만이면 BUY_IN_OUT_OF_RANGE 이고 대기열에 남지 않는다`() {
        val e = assertFailsWith<BuyInOutOfRangeException> {
            service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블", buyIn = 3_800))
        }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
        assertTrue(!joinQueue.isQueued(UserId(1L)))
    }

    @Test
    fun `STOMP 연결이 없으면 NOT_CONNECTED 이고 대기열에 남지 않는다`() {
        userConnections.connected.clear()

        val e = assertFailsWith<NotConnectedException> {
            service.create(CreateTableCommand(ownerUserId = 1, name = "새 테이블", buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.NOT_CONNECTED, e.errorCode)
        assertTrue(!joinQueue.isQueued(UserId(1L)))
    }
}
