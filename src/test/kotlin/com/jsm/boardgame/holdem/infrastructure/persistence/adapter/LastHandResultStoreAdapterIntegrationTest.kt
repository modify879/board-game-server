package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LastHandResultStoreAdapterIntegrationTest {

    @Autowired
    private lateinit var store: LastHandResultStore

    @Autowired
    private lateinit var tables: HoldemTableRepository

    private fun newTableId(): TableId = tables.save(HoldemTable.create("t-${System.nanoTime()}")).id!!

    private fun result(amount: Long) = LastHandResult(
        board = listOf("As", "Kd", "7c", "2h", "9s"),
        payouts = listOf(LastHandResult.Payout(2, 2000L, amount)),
        shownHands = listOf(LastHandResult.ShownHand(2, 2000L, listOf("Ah", "Ad"), "THREE_OF_A_KIND")),
    )

    @Test
    fun `저장한 직전 핸드 결과를 그대로 읽는다`() {
        val tableId = newTableId()

        store.save(tableId, result(300))

        assertEquals(result(300), store.find(tableId))
    }

    @Test
    fun `같은 테이블에 다시 저장하면 새 값으로 덮어쓴다`() {
        val tableId = newTableId()
        store.save(tableId, result(300))

        store.save(tableId, result(500))

        assertEquals(result(500), store.find(tableId))
    }

    @Test
    fun `지우면 읽히지 않고 없는 행을 지워도 실패하지 않는다`() {
        val tableId = newTableId()
        store.save(tableId, result(300))

        store.remove(tableId)
        store.remove(tableId)

        assertNull(store.find(tableId))
    }
}
