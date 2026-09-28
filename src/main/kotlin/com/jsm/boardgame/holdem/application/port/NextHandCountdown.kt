package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import java.time.Duration

/**
 * 다음 핸드 카운트다운. 상대 시간(단조 시계)만 쓴다 — 벽시계로 다시 비교하지 않는다. 메모리에만
 * 있고 재시작하면 HandRecovery 가 새로 건다.
 */
interface NextHandCountdown {
    /** 그 테이블에 이미 걸린 카운트다운을 취소하고 설정된 지연(`app.holdem.next-hand-delay`)
     *  전체로 처음부터 다시 건다(사용자 결정: 카운트다운 도중 새 참가자가 들어오면 리셋한다).
     *  걸려 있지 않았으면 새로 건다. */
    fun restart(tableId: TableId)

    fun cancel(tableId: TableId)

    /** 남은 시간(단조 시계 기준). 걸려 있지 않으면 null. 0 미만으로는 내려가지 않는다. */
    fun remaining(tableId: TableId): Duration?
}
