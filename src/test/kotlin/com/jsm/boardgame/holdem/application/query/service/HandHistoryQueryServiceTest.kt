package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.query.port.HandHistoryQueryRepository
import com.jsm.boardgame.holdem.application.query.port.HandHistoryQueryRepository.HandRow
import com.jsm.boardgame.holdem.application.query.port.HandHistoryQueryRepository.PlayerRow
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class HandHistoryFakeQueryRepository(private val hands: List<HandRow>, private val players: List<PlayerRow>) : HandHistoryQueryRepository {
    var lastFilter: Pair<Long?, Long?>? = null

    override fun findHands(playerUserId: Long?, tableId: Long?, pageable: Pageable): Page<HandRow> {
        lastFilter = playerUserId to tableId
        return PageImpl(hands, pageable, hands.size.toLong())
    }

    override fun findPlayers(handIds: List<Long>): List<PlayerRow> = players.filter { it.handId in handIds }
}

class HandHistoryQueryServiceTest {

    private val hand = HandRow(10, 1, Instant.parse("2026-10-11T00:00:00Z"), 1, "Kd Qc Jh")
    private val players = listOf(
        PlayerRow(10, 2, 200, 1_000, 1_000, 0, "7c 2d", false), // 폴드, 비공개
        PlayerRow(10, 1, 100, 1_000, 1_100, 100, "As Ah", false), // 조회자, 비공개
        PlayerRow(10, 3, 300, 1_000, 900, 0, "Tc Td", true), // 공개
    )
    private val repo = HandHistoryFakeQueryRepository(listOf(hand), players)
    private val service = HandHistoryQueryService(repo)
    private val page = PageRequest.of(0, 20)

    @Test
    fun `내 조회는 내 패와 공개된 패만 보이고 나머지는 null 이다`() {
        val view = service.findMine(100, page).content.single()

        assertEquals(listOf("Kd", "Qc", "Jh"), view.board)
        assertEquals(listOf(1, 2, 3), view.players.map { it.seatNo })
        assertEquals(listOf("As", "Ah"), view.players[0].holeCards)
        assertNull(view.players[1].holeCards)
        assertEquals(listOf("Tc", "Td"), view.players[2].holeCards)
        assertEquals(100L to null, repo.lastFilter)
    }

    @Test
    fun `관리자 조회는 마스킹하지 않고 필터를 그대로 넘긴다`() {
        val view = service.findAll(200, 1, page).content.single()

        assertEquals(listOf(listOf("As", "Ah"), listOf("7c", "2d"), listOf("Tc", "Td")), view.players.map { it.holeCards })
        assertEquals(200L to 1L, repo.lastFilter)
    }

    @Test
    fun `보드가 없는 핸드는 빈 리스트다`() {
        val empty = HandHistoryQueryService(HandHistoryFakeQueryRepository(listOf(hand.copy(board = "")), players))

        assertEquals(emptyList(), empty.findMine(100, page).content.single().board)
    }
}
