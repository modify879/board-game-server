package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.application.command.usecase.ShowHandCommand
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.FakeFoldWinnerCards
import com.jsm.boardgame.holdem.application.port.FakeHandHistory
import com.jsm.boardgame.holdem.application.port.FoldWinnerCards.FoldWinner
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class ShowHandFakeTableRepository(private val table: HoldemTable) : HoldemTableRepository {
    override fun findById(id: TableId): HoldemTable? = table.takeIf { it.id == id }
    override fun findByUserId(userId: UserId): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<UserId> = emptyList()
    override fun save(table: HoldemTable): HoldemTable = table
    override fun findAllTableIds(): List<TableId> = listOfNotNull(table.id)
}

private class ShowHandFakeHandStore : HandStore {
    val store = mutableMapOf<Long, Hand>()
    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

private class ShowHandFakeLastHandResultStore : LastHandResultStore {
    val store = mutableMapOf<Long, LastHandResult>()
    override fun find(tableId: TableId): LastHandResult? = store[tableId.value]
    override fun save(tableId: TableId, result: LastHandResult) { store[tableId.value] = result }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
}

class ShowHandServiceTest {

    private val tableId = TableId(1)
    private val winnerUser = UserId(2000L)
    private val table = HoldemTable.reconstitute(
        id = tableId,
        name = "t",
        smallBlind = Chips.of(100),
        bigBlind = Chips.of(200),
        buttonSeatNo = 1,
        seats = (1..2).associateWith {
            Seat.reconstitute(it, userId = UserId(it * 1000L), stack = Chips.of(10_000), presence = SeatPresence.SEATED)
        },
    )
    private val handStore = ShowHandFakeHandStore()
    private val foldWinnerCards = FakeFoldWinnerCards()
    private val lastHandResults = ShowHandFakeLastHandResultStore()
    private val handHistory = FakeHandHistory()
    private val events = mutableListOf<Any>()
    private val service = ShowHandService(
        ShowHandFakeTableRepository(table), handStore, foldWinnerCards, lastHandResults, handHistory, ApplicationEventPublisher { events += it },
    )

    private val winner = FoldWinner(2, winnerUser, listOf("As", "Ah"))

    private fun endedByFold(board: List<String>) {
        foldWinnerCards.save(tableId, winner)
        lastHandResults.save(tableId, LastHandResult(board, listOf(LastHandResult.Payout(2, winnerUser, 300)), emptyList()))
    }

    private fun assertShowRejected(userId: Long, errorCode: HoldemErrorCode) {
        val e = assertFailsWith<BusinessException> { service.show(ShowHandCommand(1, userId)) }
        assertEquals(errorCode, e.errorCode)
    }

    @Test
    fun `폴드 승자가 보여 주면 공개 결과에 두 장이 붙고 포트가 비워지며 브로드캐스트가 요청된다`() {
        endedByFold(listOf("Kd", "Qc", "Jh"))

        service.show(ShowHandCommand(1, winnerUser.value))

        val shown = lastHandResults.find(tableId)!!.shownHands.single()
        assertEquals(LastHandResult.ShownHand(2, winnerUser, listOf("As", "Ah"), "PAIR"), shown)
        assertNull(foldWinnerCards.find(tableId))
        assertEquals(listOf(tableId to winnerUser), handHistory.shown)
        val event = events.filterIsInstance<HandBroadcastRequested>().single()
        assertEquals(tableId, event.tableId)
        assertNull(event.hand)
    }

    @Test
    fun `프리플랍 폴드 승리는 족보 이름이 null 이다`() {
        endedByFold(emptyList())

        service.show(ShowHandCommand(1, winnerUser.value))

        assertNull(lastHandResults.find(tableId)!!.shownHands.single().category)
    }

    @Test
    fun `승자가 아닌 사용자는 SHOW_NOT_ALLOWED 이고 아무것도 바뀌지 않는다`() {
        endedByFold(listOf("Kd", "Qc", "Jh"))

        assertShowRejected(1000L, HoldemErrorCode.SHOW_NOT_ALLOWED)

        assertEquals(winner, foldWinnerCards.find(tableId))
        assertEquals(emptyList(), lastHandResults.find(tableId)!!.shownHands)
        assertEquals(emptyList(), events)
        assertEquals(emptyList(), handHistory.shown)
    }

    @Test
    fun `쇼다운으로 끝난 핸드는 저장된 승자가 없어 SHOW_NOT_ALLOWED 이다`() {
        lastHandResults.save(tableId, LastHandResult(listOf("Kd", "Qc", "Jh", "9s", "4h"), emptyList(), emptyList()))

        assertShowRejected(winnerUser.value, HoldemErrorCode.SHOW_NOT_ALLOWED)
    }

    @Test
    fun `핸드가 진행 중이면 HAND_IN_PROGRESS 이다`() {
        endedByFold(listOf("Kd", "Qc", "Jh"))
        val stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000))
        val hand = Hand.start(stacks, 1, 1, 2, Chips.of(100), Chips.of(200), Shuffler { it })
        handStore.save(tableId, hand)

        assertShowRejected(winnerUser.value, HoldemErrorCode.HAND_IN_PROGRESS)
    }

    @Test
    fun `두 번째로 보여 주려 하면 SHOW_NOT_ALLOWED 이다`() {
        endedByFold(listOf("Kd", "Qc", "Jh"))
        service.show(ShowHandCommand(1, winnerUser.value))

        assertShowRejected(winnerUser.value, HoldemErrorCode.SHOW_NOT_ALLOWED)

        assertEquals(1, lastHandResults.find(tableId)!!.shownHands.size)
    }

    @Test
    fun `없는 테이블이면 TABLE_NOT_FOUND 이다`() {
        val e = assertFailsWith<BusinessException> { service.show(ShowHandCommand(99, winnerUser.value)) }
        assertEquals(HoldemErrorCode.TABLE_NOT_FOUND, e.errorCode)
    }
}
