package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import java.time.Duration

/**
 * 차례 타이머의 남은 시간 조회. 상대 시간(단조 시계)만 쓴다 — 벽시계로 다시 비교하지 않는다.
 * 메모리에만 있고 재시작하면 복구 뒤 새로 걸린다.
 */
interface TurnCountdown {
    /** 모든 차례가 받는 전체 길이. 차례마다 이 값으로 예약한다. */
    val turnTimeout: Duration

    /** 그 테이블의 현재 차례가 만료되기까지 남은 시간(단조 시계 기준). 예약이 없으면 null.
     *  0 미만으로는 내려가지 않는다. */
    fun remaining(tableId: TableId): Duration?
}
