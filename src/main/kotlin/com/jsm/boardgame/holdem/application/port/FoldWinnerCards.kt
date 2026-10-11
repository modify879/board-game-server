package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId

/**
 * 모두 폴드해 이긴 좌석의 홀카드 — 승자가 "보여 주기"(하우스 룰 10) 를 고를 때까지만 보관한다.
 * [LastHandResult] 는 공개 결과의 출처라 비공개 카드를 담지 않는다. 좌석 주인이 바뀔 수 있어 [userId] 를 함께 담는다.
 */
interface FoldWinnerCards {
    data class FoldWinner(val seatNo: Int, val userId: UserId, val holeCards: List<String>)

    fun save(tableId: TableId, winner: FoldWinner)
    fun find(tableId: TableId): FoldWinner?
    fun remove(tableId: TableId)
}
