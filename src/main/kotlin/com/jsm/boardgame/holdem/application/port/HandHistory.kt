package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId

/**
 * 끝난 핸드의 결과 요약을 남기는 추가 전용 로그(하우스 룰 11). 도메인 모델이 아니다 — 쓰기만 있고 도메인 규칙이 없다.
 * 끝난 핸드만 남긴다: 취소된 핸드(CancelHandService)는 기록하지 않는다. 종료 시각은 어댑터가 공유 `Clock` 으로 찍는다.
 */
interface HandHistory {
    fun record(entry: HandHistoryEntry)

    /** [tableId] 의 가장 최근 히스토리에서 [userId] 의 패를 공개한 것으로 표시한다(폴드 승자의 보여 주기). */
    fun markShown(tableId: TableId, userId: UserId)
}

data class HandHistoryEntry(
    val tableId: Long,
    val buttonSeatNo: Int,
    val board: List<String>,
    val players: List<Player>,
) {
    data class Player(
        val seatNo: Int,
        val userId: Long,
        val startingStack: Long,
        val endingStack: Long,
        val payout: Long,
        val holeCards: List<String>,
        val shown: Boolean,
    )
}
