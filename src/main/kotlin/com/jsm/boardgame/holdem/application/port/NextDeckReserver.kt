package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 테이블의 "다음 핸드 하나"의 덱을 고정 예약하는 QA 전용 포트. 같은 테이블에 다시 예약하면 덮어쓴다.
 * 사양이 잘못되면(좌석 범위·장수·중복) [IllegalArgumentException].
 */
interface NextDeckReserver {
    fun reserve(tableId: TableId, holeCards: Map<Int, List<Card>>, board: List<Card>)
}
