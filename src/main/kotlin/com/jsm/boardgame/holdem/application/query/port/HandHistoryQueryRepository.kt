package com.jsm.boardgame.holdem.application.query.port

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

interface HandHistoryQueryRepository {
    /** 종료 시각 내림차순. [playerUserId] 가 있으면 그 사용자가 참가한 핸드만, [tableId] 가 있으면 그 테이블 것만. */
    fun findHands(playerUserId: Long?, tableId: Long?, pageable: Pageable): Page<HandRow>

    fun findPlayers(handIds: List<Long>): List<PlayerRow>

    /** board·holeCards 는 카드를 공백으로 이은 저장 형태 그대로다. 마스킹은 서비스가 한다. */
    data class HandRow(val handId: Long, val tableId: Long, val endedAt: Instant, val buttonSeatNo: Int, val board: String)

    data class PlayerRow(
        val handId: Long,
        val seatNo: Int,
        val userId: Long,
        val startingStack: Long,
        val endingStack: Long,
        val payout: Long,
        val holeCards: String,
        val shown: Boolean,
    )
}
