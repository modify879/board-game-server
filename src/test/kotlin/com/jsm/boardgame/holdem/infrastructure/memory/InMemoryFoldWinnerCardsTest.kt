package com.jsm.boardgame.holdem.infrastructure.memory

import com.jsm.boardgame.holdem.application.port.FoldWinnerCards.FoldWinner
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryFoldWinnerCardsTest {

    private val cards = InMemoryFoldWinnerCards()
    private val tableId = TableId(1)
    private val winner = FoldWinner(2, UserId(11), listOf("As", "Ah"))

    @AfterTest
    fun clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clear()
    }

    private fun commit() = TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }

    @Test
    fun `트랜잭션 밖이면 save 와 remove 가 즉시 반영된다`() {
        cards.save(tableId, winner)
        assertEquals(winner, cards.find(tableId))

        cards.remove(tableId)
        assertNull(cards.find(tableId))
    }

    @Test
    fun `롤백된 트랜잭션의 save 는 남지 않는다`() {
        TransactionSynchronizationManager.initSynchronization()

        cards.save(tableId, winner)

        assertNull(cards.find(tableId))
        // 커밋 콜백 없이 종료 = 롤백
        TransactionSynchronizationManager.clear()
        assertNull(cards.find(tableId))
    }

    @Test
    fun `커밋된 트랜잭션의 save 와 remove 는 커밋 뒤에 보인다`() {
        TransactionSynchronizationManager.initSynchronization()
        cards.save(tableId, winner)
        assertNull(cards.find(tableId))
        commit()
        TransactionSynchronizationManager.clear()
        assertEquals(winner, cards.find(tableId))

        TransactionSynchronizationManager.initSynchronization()
        cards.remove(tableId)
        assertEquals(winner, cards.find(tableId))
        commit()
        assertNull(cards.find(tableId))
    }
}
