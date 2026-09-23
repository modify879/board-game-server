package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandInProgressJpaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HandInProgressStoreAdapterIntegrationTest {

    @Autowired
    private lateinit var handStore: HandStore

    @Autowired
    private lateinit var handJpa: HandInProgressJpaRepository

    @Autowired
    private lateinit var tables: HoldemTableRepository

    @Autowired
    private lateinit var shuffler: Shuffler

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    // fk_hand_in_progress_table 때문에 실제 holdem_tables 행이 있어야 한다.
    private fun newTableId(): TableId = tables.save(HoldemTable.create("t-${System.nanoTime()}")).id!!

    private fun newHand(): Hand = Hand.start(
        stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
        buttonSeatNo = 1,
        smallBlindSeatNo = 2,
        bigBlindSeatNo = 3,
        smallBlind = Chips.of(100),
        bigBlind = Chips.of(200),
        shuffler = shuffler,
    )

    @Test
    fun `저장 후 복원한 핸드는 같은 차례 좌석·같은 스냅샷·같은 보드·같은 홀카드를 갖는다`() {
        val tableId = newTableId()
        val hand = newHand()

        handStore.save(tableId, hand)
        val found = handStore.find(tableId)

        assertNotNull(found)
        assertEquals(hand.snapshot(), found.snapshot())
        assertEquals(hand.toActSeatNo, found.toActSeatNo)
        assertEquals(hand.board, found.board)
        for (seatNo in hand.seatNos) {
            assertEquals(hand.holeCardsOf(seatNo), found.holeCardsOf(seatNo))
        }
    }

    @Test
    fun `같은 테이블에 두 번 저장하면 행 하나가 덮어써진다`() {
        val tableId = newTableId()
        val hand = newHand()
        handStore.save(tableId, hand)

        val actingSeatNo = hand.toActSeatNo!!
        hand.act(actingSeatNo, BettingAction.Call)
        handStore.save(tableId, hand)

        val rowCount = jdbcTemplate.queryForObject(
            "select count(*) from holdem_hand_in_progress where table_id = ?",
            Long::class.java,
            tableId.value,
        )
        assertEquals(1L, rowCount)

        val found = handStore.find(tableId)
        assertEquals(hand.toActSeatNo, found?.toActSeatNo)
    }

    @Test
    fun `remove 후 find 는 null 이다`() {
        val tableId = newTableId()
        handStore.save(tableId, newHand())

        handStore.remove(tableId)

        assertNull(handStore.find(tableId))
    }

    @Test
    fun `한 번도 저장한 적 없는 테이블의 remove 는 조용히 아무 일도 하지 않는다`() {
        val tableId = newTableId()

        handStore.remove(tableId)

        assertNull(handStore.find(tableId))
    }

    @Test
    fun `저장된 state JSON 에는 아직 딜되지 않은 카드가 없다`() {
        val tableId = newTableId()
        val hand = newHand()
        handStore.save(tableId, hand)

        val rawState = jdbcTemplate.queryForObject(
            "select state::text from holdem_hand_in_progress where table_id = ?",
            String::class.java,
            tableId.value,
        )
        assertNotNull(rawState)
        assertFalse(rawState.contains("deck", ignoreCase = true))

        val dealtCards = hand.seatNos.flatMap { hand.holeCardsOf(it) }.map { it.toString() }.toSet() + hand.board.map { it.toString() }
        val cardTokenPattern = Regex("\"([2-9TJQKA][shdc])\"")
        val cardsInJson = cardTokenPattern.findAll(rawState).map { it.groupValues[1] }.toSet()
        assertEquals(dealtCards, cardsInJson)
    }

    @Test
    fun `복원한 핸드로 액션을 이어 진행할 수 있다`() {
        val tableId = newTableId()
        val hand = newHand()
        handStore.save(tableId, hand)

        val found = handStore.find(tableId)!!
        val actingSeatNo = found.toActSeatNo!!
        found.act(actingSeatNo, BettingAction.Call)

        assertNotNull(found.toActSeatNo)
        assertFalse(found.toActSeatNo == actingSeatNo)
    }
}
