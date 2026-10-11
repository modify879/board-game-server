package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.port.HandHistoryEntry
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryPlayerJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryPlayerJpaRepository
import com.jsm.boardgame.holdem.infrastructure.timer.HandHistoryPurger
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 히스토리 어댑터(기록·shown 갱신)와 90일 보관 삭제(FK cascade 포함). 테이블 id 는 다른 테스트와 겹치지 않는 대역을 쓴다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HandHistoryAdapterIntegrationTest {

    @Autowired private lateinit var adapter: HandHistoryAdapter
    @Autowired private lateinit var hands: HandHistoryJpaRepository
    @Autowired private lateinit var players: HandHistoryPlayerJpaRepository
    @Autowired private lateinit var purger: HandHistoryPurger
    @Autowired private lateinit var clock: Clock

    private fun entry(tableId: Long) = HandHistoryEntry(
        tableId, 1, listOf("Kd", "Qc", "Jh"),
        listOf(
            HandHistoryEntry.Player(1, 11, 1_000, 1_100, 100, listOf("As", "Ah"), false),
            HandHistoryEntry.Player(2, 22, 1_000, 900, 0, listOf("7c", "2d"), false),
        ),
    )

    @Test
    fun `markShown 은 그 테이블의 가장 최근 핸드에서 그 사용자만 공개로 바꾼다`() {
        val tableId = 7_000_000_001L
        adapter.record(entry(tableId))
        adapter.record(entry(tableId))
        val (older, latest) = hands.findAll().filter { it.tableId == tableId }.sortedBy { it.id }

        adapter.markShown(TableId(tableId), UserId(11))

        val byHand = players.findAll().filter { it.handId in listOf(older.id, latest.id) }.groupBy { it.handId }
        assertTrue(byHand.getValue(latest.id).single { it.userId == 11L }.shown)
        assertFalse(byHand.getValue(latest.id).single { it.userId == 22L }.shown)
        assertFalse(byHand.getValue(older.id).any { it.shown })
        assertEquals("Kd Qc Jh", latest.board)
    }

    @Test
    fun `보관 기간이 지난 핸드만 플레이어 행과 함께 지운다`() {
        val tableId = 7_000_000_002L
        val now = clock.instant()
        val old = hands.save(HandHistoryJpaEntity(tableId = tableId, endedAt = now.minus(Duration.ofDays(91)), buttonSeatNo = 1, board = ""))
        val fresh = hands.save(HandHistoryJpaEntity(tableId = tableId, endedAt = now.minus(Duration.ofDays(89)), buttonSeatNo = 1, board = ""))
        listOf(old, fresh).forEach {
            players.save(HandHistoryPlayerJpaEntity(handId = it.id, userId = 11, seatNo = 1, startingStack = 1, endingStack = 1, payout = 0, holeCards = "As Ah", shown = false))
        }

        purger.purge()

        assertFalse(hands.existsById(old.id))
        assertTrue(hands.existsById(fresh.id))
        assertEquals(emptyList(), players.findAll().filter { it.handId == old.id })
        assertEquals(1, players.findAll().count { it.handId == fresh.id })
    }
}
