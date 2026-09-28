package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.NotConnectedException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SitDownFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllTableIds(): List<TableId> = stored.values.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
        )
        stored[id.value] = saved
        return saved
    }
}

private class SitDownFakeHandStore : HandStore {
    val stored = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = stored[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { stored[tableId.value] = hand }
    override fun remove(tableId: TableId) { stored.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = stored.keys.map { TableId(it) }
}

private class SitDownFakeUserConnections : UserConnections {
    private val connectedUserIds: MutableSet<Long> = mutableSetOf()

    fun connect(userId: Long) { connectedUserIds += userId }

    override fun isConnected(userId: Long): Boolean = userId in connectedUserIds

    override fun hasOtherSession(userId: Long, excludingSessionId: String): Boolean = false
}

class SitDownServiceTest {

    private val tables = SitDownFakeHoldemTableRepository()
    private val handStore = SitDownFakeHandStore()
    private val joinQueue = InMemoryJoinQueue()
    private val userConnections = SitDownFakeUserConnections()
    private val eventPublisher = ApplicationEventPublisher { }
    private val service = SitDownService(tables, handStore, joinQueue, userConnections, eventPublisher)

    private fun createTable(): TableId = tables.save(HoldemTable.create("테스트 테이블")).id!!

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
        val otherTable = tables.save(HoldemTable.create("다른 테이블"))
        otherTable.sitDown(userId = 1, buyIn = Chips.of(8_000))
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
        val otherTable = tables.save(HoldemTable.create("다른 테이블"))
        otherTable.sitDown(userId = 1, buyIn = Chips.of(8_000))
        val savedOther = tables.save(otherTable)
        handStore.save(
            savedOther.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it }),
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
        joinQueue.enqueue(otherTableId, 1, Chips.of(8_000), false)
        val tableId = createTable()
        userConnections.connect(1)

        val e = assertFailsWith<AlreadySeatedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `바이인이 빅블라인드 미만이면 BUY_IN_OUT_OF_RANGE`() {
        val tableId = createTable()
        userConnections.connect(1)

        val e = assertFailsWith<BuyInOutOfRangeException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 100))
        }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
    }

    @Test
    fun `STOMP 연결이 없으면 NOT_CONNECTED 이고 대기열에 들어가지 않는다`() {
        val tableId = createTable()

        val e = assertFailsWith<NotConnectedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.NOT_CONNECTED, e.errorCode)
        assertFalse(joinQueue.isQueued(1))
    }

    @Test
    fun `연결된 사용자가 착석하면 대기열 1번으로 들어간다`() {
        val tableId = createTable()
        userConnections.connect(1)

        val position = service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000, postBlindImmediately = true))

        assertEquals(1, position)
        val entry = joinQueue.entriesOf(tableId).single()
        assertEquals(1L, entry.userId)
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
        assertEquals(listOf(1L, 2L), entries.map { it.userId })
    }

    @Test
    fun `postBlindImmediately 를 생략하면 대기열 항목에도 false 로 남는다`() {
        val tableId = createTable()
        userConnections.connect(1)

        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, buyIn = 8_000))

        assertFalse(joinQueue.entriesOf(tableId).single().postBlindImmediately)
    }
}
