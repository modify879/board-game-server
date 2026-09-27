package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.holdem.domain.model.TableId

/** 대기열 상태 변화를 개인 채널(/user/queue/holdem/join-queue)로 알린다. 페이로드 조립과 실제
 *  WS 전송은 presentation 이 구현한다(규칙 6 — 페이로드 타입은 presentation 소유). */
interface JoinQueueNotifier {
    fun notifySeated(tableId: TableId, userId: Long, seatNo: Int)
    fun notifyDropped(tableId: TableId, userId: Long, errorCode: ErrorCode)
    /** [entries] 순서대로 1-based 순번을 매겨 각자에게 POSITION 을 보낸다. */
    fun notifyPositions(tableId: TableId, entries: List<JoinQueueEntry>)
}
