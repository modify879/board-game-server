package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.query.port.HandHistoryQueryRepository
import com.jsm.boardgame.holdem.application.query.view.HandHistoryView
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 하우스 룰 11. 플레이어는 자기가 참가한 핸드만 보고, 자기 패는 항상, 남의 패는 공개(shown)한 것만 본다(규칙 6 —
 * 마스킹은 여기서 한다). 관리자 조회는 마스킹이 없다.
 */
@Service
@Transactional(readOnly = true)
class HandHistoryQueryService(
    private val query: HandHistoryQueryRepository,
) {
    fun findMine(userId: Long, pageable: Pageable): Page<HandHistoryView> =
        find(playerUserId = userId, tableId = null, viewerId = userId, pageable)

    fun findAll(userId: Long?, tableId: Long?, pageable: Pageable): Page<HandHistoryView> =
        find(playerUserId = userId, tableId = tableId, viewerId = null, pageable)

    /** [viewerId] 가 null 이면 마스킹하지 않는다(관리자). */
    private fun find(playerUserId: Long?, tableId: Long?, viewerId: Long?, pageable: Pageable): Page<HandHistoryView> {
        val hands = query.findHands(playerUserId, tableId, pageable)
        val playersByHand = query.findPlayers(hands.content.map { it.handId }).groupBy { it.handId }
        return hands.map { hand ->
            HandHistoryView(
                handId = hand.handId,
                tableId = hand.tableId,
                endedAt = hand.endedAt,
                buttonSeatNo = hand.buttonSeatNo,
                board = hand.board.cards(),
                players = playersByHand[hand.handId].orEmpty().sortedBy { it.seatNo }.map { p ->
                    HandHistoryView.Player(
                        seatNo = p.seatNo,
                        userId = p.userId,
                        startingStack = p.startingStack,
                        endingStack = p.endingStack,
                        payout = p.payout,
                        holeCards = p.holeCards.cards().takeIf { viewerId == null || p.userId == viewerId || p.shown },
                        shown = p.shown,
                    )
                },
            )
        }
    }

    private fun String.cards(): List<String> = split(' ').filter { it.isNotEmpty() }
}
