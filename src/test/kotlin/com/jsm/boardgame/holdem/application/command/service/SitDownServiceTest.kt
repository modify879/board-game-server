package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.SeatTakenException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class SitDownFakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findAllSeatedUserIds(): List<Long> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            version = table.version,
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

private class SitDownFakeWalletTransfer : WalletTransfer {
    data class ToGameCall(val userId: Long, val amount: Long, val tableId: Long, val memo: String?)

    val toGameCalls = mutableListOf<ToGameCall>()

    override fun toGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        toGameCalls += ToGameCall(userId, amount, tableId, memo)
    }

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        error("SitDown 은 fromGame 을 부르지 않는다")
    }
}

class SitDownServiceTest {

    private val tables = SitDownFakeHoldemTableRepository()
    private val handStore = SitDownFakeHandStore()
    private val walletTransfer = SitDownFakeWalletTransfer()
    private val service = SitDownService(tables, handStore, walletTransfer)

    private fun createTable(): TableId = tables.save(HoldemTable.create("테스트 테이블")).id!!

    @Test
    fun `착석하면 좌석이 채워지고 지갑에서 올바른 금액·tableId 로 이체가 불린다`() {
        val tableId = createTable()

        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 3, buyIn = 8_000))

        val table = tables.findById(tableId)!!
        assertEquals(1L, table.seatAt(3)!!.userId)
        assertEquals(Chips.of(8_000), table.seatAt(3)!!.stack)

        val call = walletTransfer.toGameCalls.single()
        assertEquals(1L, call.userId)
        assertEquals(8_000L, call.amount)
        assertEquals(tableId.value, call.tableId)
    }

    @Test
    fun `존재하지 않는 테이블이면 TABLE_NOT_FOUND`() {
        val e = assertFailsWith<TableNotFoundException> {
            service.sitDown(SitDownCommand(tableId = 999, userId = 1, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `이미 다른 테이블에 앉아 있으면 핸드가 없어도 ALREADY_SEATED`() {
        val otherTable = tables.save(HoldemTable.create("다른 테이블"))
        otherTable.sitDown(1, 1, Chips.of(8_000))
        tables.save(otherTable)
        val tableId = createTable()

        val e = assertFailsWith<AlreadySeatedException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
        assertEquals(0, walletTransfer.toGameCalls.size)
    }

    @Test
    fun `이미 앉은 테이블에서 핸드가 진행 중이면 HAND_IN_PROGRESS`() {
        val otherTable = tables.save(HoldemTable.create("다른 테이블"))
        otherTable.sitDown(1, 1, Chips.of(8_000))
        val savedOther = tables.save(otherTable)
        handStore.save(
            savedOther.id!!,
            Hand.start(mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)), buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, HoldemTable.SMALL_BLIND, HoldemTable.BIG_BLIND, Shuffler { it }),
        )
        val tableId = createTable()

        val e = assertFailsWith<HandInProgressException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, e.errorCode)
        assertEquals(0, walletTransfer.toGameCalls.size)
    }

    @Test
    fun `이미 점유된 좌석이면 도메인 예외 SEAT_TAKEN 이 그대로 올라오고 지갑은 불리지 않는다`() {
        val tableId = createTable()
        service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 8_000))

        val e = assertFailsWith<SeatTakenException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 2, seatNo = 1, buyIn = 8_000))
        }
        assertEquals(HoldemErrorCode.SEAT_TAKEN, e.errorCode)
        assertEquals(1, walletTransfer.toGameCalls.size) // 첫 착석분만
    }

    @Test
    fun `바이인이 범위를 벗어나면 도메인 예외 BUY_IN_OUT_OF_RANGE 가 그대로 올라오고 지갑은 불리지 않는다`() {
        val tableId = createTable()

        val e = assertFailsWith<BuyInOutOfRangeException> {
            service.sitDown(SitDownCommand(tableId = tableId.value, userId = 1, seatNo = 1, buyIn = 100))
        }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
        assertEquals(0, walletTransfer.toGameCalls.size)
    }
}
