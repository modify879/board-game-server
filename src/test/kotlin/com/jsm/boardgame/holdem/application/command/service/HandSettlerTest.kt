package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class HandSettlerFakeTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]

    override fun findByUserId(userId: Long): HoldemTable? = store.values.find { it.seatOf(userId) != null }

    override fun findAllSeatedUserIds(): List<Long> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: TableId(nextId++)
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
            version = table.version,
        )
        store[id.value] = saved
        return saved
    }
}

private class HandSettlerFakeHandStore : HandStore {
    private val store = mutableMapOf<Long, Hand>()

    override fun find(tableId: TableId): Hand? = store[tableId.value]
    override fun save(tableId: TableId, hand: Hand) { store[tableId.value] = hand }
    override fun remove(tableId: TableId) { store.remove(tableId.value) }
    override fun findAllInProgress(): List<TableId> = store.keys.map { TableId(it) }
}

/**
 * 좌석 1 = As Ah(포켓 에이스), 좌석 2 = 7c 2d(무패). 보드는 Kd Qc Jh 9s 4h — 좌석 2 에는
 * 아무 도움도 안 되고 좌석 1 은 원페어를 완성해 쇼다운에서 반드시 이긴다. 무작위 셔플에 기대는
 * 대신 카드 강도로 승부를 고정해, 패자가 항상 좌석 2 가 되도록 만든다.
 * 딜은 버튼(1) 다음인 좌석 2 부터 시작해 버튼이 마지막 카드를 받으므로, 위 배정을 유지하려면
 * 좌석 2 의 카드를 먼저 둔다.
 */
private val bustingHandOrder: List<Card> = listOf(
    Card.of("7c"), Card.of("As"), Card.of("2d"), Card.of("Ah"),
    Card.of("Kd"), Card.of("Qc"), Card.of("Jh"), Card.of("9s"), Card.of("4h"),
)
private val bustingShuffler = Shuffler { full -> bustingHandOrder + full.filterNot { it in bustingHandOrder } }
private val identityShuffler = Shuffler { it }

class HandSettlerTest {

    private val tables = HandSettlerFakeTableRepository()
    private val handStore = HandSettlerFakeHandStore()
    private val eventPublisher = ApplicationEventPublisher { }

    // HandSettler 생성자에 WalletTransfer 가 없다 — 자동 기립이 지갑 이체를 부를 방법 자체가 없다
    // (StandUpService 가 스택 0일 때 이체를 건너뛰는 것과 같은 이유).
    private val settler = HandSettler(tables, handStore, eventPublisher)

    private fun seatedTable(vararg stacks: Pair<Int, Long>): HoldemTable {
        val seats = stacks.associate { (seatNo, stack) ->
            seatNo to Seat.reconstitute(seatNo, userId = seatNo * 1000L, stack = Chips.of(stack), presence = SeatPresence.SEATED)
        }
        val table = HoldemTable.reconstitute(
            id = TableId(1),
            name = "test-table",
            smallBlind = Chips.of(100),
            bigBlind = Chips.of(200),
            buttonSeatNo = 1,
            seats = seats,
            version = 0,
        )
        return tables.save(table)
    }

    @Test
    fun `정산 후 스택이 0이 된 좌석은 지갑 이체 없이 자동으로 기립한다`() {
        val table = seatedTable(1 to 200L, 2 to 200L)
        val tableId = table.id!!
        val stacks = mapOf(1 to Chips.of(200), 2 to Chips.of(200))
        val hand = Hand.start(stacks, buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, Chips.of(100), Chips.of(200), bustingShuffler)
        handStore.save(tableId, hand)

        // 좌석 1(버튼/SB)이 남은 스택을 콜해 둘 다 올인 — 액션 없이 쇼다운까지 진행된다.
        hand.act(1, BettingAction.Call)

        settler.settle(tableId, table, hand)

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        val survivor = savedTable.seatAt(1)
        assertNotNull(survivor)
        assertEquals(Chips.of(400), survivor.stack)
        assertNull(savedTable.seatAt(2))
    }

    @Test
    fun `스택이 남은 좌석은 정산 후에도 그대로 앉아 있고 handStore 에서는 제거된다`() {
        val table = seatedTable(1 to 10_000L, 2 to 10_000L)
        val tableId = table.id!!
        val stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000))
        val hand = Hand.start(stacks, buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, Chips.of(100), Chips.of(200), identityShuffler)
        handStore.save(tableId, hand)

        // 좌석 1(버튼/SB)이 바로 폴드 — 둘 다 스택이 남는다.
        hand.act(1, BettingAction.Fold)

        settler.settle(tableId, table, hand)

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        val seat1 = savedTable.seatAt(1)
        val seat2 = savedTable.seatAt(2)
        assertNotNull(seat1)
        assertNotNull(seat2)
        assertEquals(Chips.of(20_000), seat1.stack + seat2.stack)
    }

    @Test
    fun `핸드 도중 기립한 폴드 좌석은 정산에서 건너뛰고 칩은 보존된다`() {
        // seat2 는 이미 ExpireTurnService 가 1분 무응답 폴드로 기립시켜 테이블에 없다고 가정한다.
        val table = seatedTable(1 to 10_000L, 3 to 10_000L)
        val tableId = table.id!!
        val stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000))
        val hand = Hand.start(stacks, buttonSeatNo = 1, smallBlindSeatNo = 2, bigBlindSeatNo = 3, Chips.of(100), Chips.of(200), identityShuffler)
        hand.act(1, BettingAction.Call)
        hand.act(2, BettingAction.Fold)
        hand.act(3, BettingAction.Fold)
        handStore.save(tableId, hand)
        assertTrue(hand.isFinished)

        settler.settle(tableId, table, hand)

        assertNull(handStore.find(tableId))
        val savedTable = tables.findById(tableId)!!
        assertNull(savedTable.seatAt(2))
        val seat1 = savedTable.seatAt(1)!!
        val seat3 = savedTable.seatAt(3)!!
        // seat2 몫(hand.stackOf(2))은 정산 이전에 이미 지갑으로 나갔다고 가정한다 — 셋을 합치면 시작 총액과 같다.
        assertEquals(Chips.of(30_000), seat1.stack + seat3.stack + hand.stackOf(2))
    }

    @Test
    fun `점유되지 않았는데 폴드도 아닌 좌석이 섞이면 불변식 위반으로 터진다`() {
        val table = seatedTable(1 to 10_000L) // seat2 는 애초에 앉지 않았다 - 정상 상황이 아니다.
        val tableId = table.id!!
        val stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000))
        val hand = Hand.start(stacks, buttonSeatNo = 1, smallBlindSeatNo = 1, bigBlindSeatNo = 2, Chips.of(100), Chips.of(200), identityShuffler)
        hand.act(1, BettingAction.Fold) // 좌석2 가 이겨 핸드는 끝나지만, 좌석2 는 원래 앉아있지도 않았다.

        assertFailsWith<IllegalStateException> {
            settler.settle(tableId, table, hand)
        }
    }
}
