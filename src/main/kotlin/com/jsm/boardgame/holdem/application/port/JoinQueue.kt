package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 착석 대기열. 좌석 배정 전이라 돈이 걸려 있지 않다 — 재시작하면 비워져도 안전하다
 * (좌석·잔액은 여전히 DB 로 보장된다). 단일 인스턴스 배포를 전제한다(구현체 KDoc 참고).
 */
interface JoinQueue {
    /** userId 는 테이블 전역에서 한 번에 하나의 대기열에만 있을 수 있다 — 이미 대기 중이면
     *  [AlreadySeatedException] 을 던진다(구현이 원자적으로 검사+삽입한다). 1-based 대기 순번을 돌려준다. */
    fun enqueue(tableId: TableId, userId: Long, buyIn: Chips, postBlindImmediately: Boolean): Int

    fun peekHead(tableId: TableId): JoinQueueEntry?

    /** 대기열에서 제거한다. 어느 테이블에 있었는지 돌려준다(없었으면 null). */
    fun removeByUserId(userId: Long): TableId?

    /** [tableId] 의 대기열을 순서대로. 1-based 순번은 호출자가 index+1 로 매긴다. */
    fun entriesOf(tableId: TableId): List<JoinQueueEntry>

    fun isQueued(userId: Long): Boolean

    /** userId 가 대기 중인 테이블. 대기 중이 아니면 null. */
    fun tableOf(userId: Long): TableId?
}

data class JoinQueueEntry(val userId: Long, val buyIn: Chips, val postBlindImmediately: Boolean)
