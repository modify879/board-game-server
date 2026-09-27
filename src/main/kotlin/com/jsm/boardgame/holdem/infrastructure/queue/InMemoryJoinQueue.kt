package com.jsm.boardgame.holdem.infrastructure.queue

import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.stereotype.Component

/**
 * [JoinQueue] 의 인메모리 구현. 단일 인스턴스 배포를 전제한다 — 여러 인스턴스면 각자 자기
 * 대기열만 보게 된다(TableViewSequence·타이머들과 같은 전제). 재시작하면 비워지지만 좌석 배정
 * 전이라 안전하다(클래스 KDoc 참고).
 *
 * ponytail: 락 하나로 모든 테이블을 직렬화한다 — 대기열 크기는 사람 수 정도로 작고, 오래 걸리는
 * 작업(지갑 이체·DB 저장)은 이 락 밖(AdmitJoinRequestService 의 REQUIRES_NEW 트랜잭션)에서
 * 일어나므로 경합이 오래 붙들리지 않는다. 테이블별 트래픽이 실제로 이 락에서 병목이 되면
 * 테이블별 락으로 바꾼다.
 */
@Component
class InMemoryJoinQueue : JoinQueue {
    private data class Entry(val userId: Long, val buyIn: Chips, val postBlindImmediately: Boolean) {
        fun toView() = JoinQueueEntry(userId, buyIn, postBlindImmediately)
    }

    private val lock = Any()
    private val tableQueues = mutableMapOf<Long, MutableList<Entry>>()
    private val userToTable = mutableMapOf<Long, Long>()

    override fun enqueue(tableId: TableId, userId: Long, buyIn: Chips, postBlindImmediately: Boolean): Int =
        synchronized(lock) {
            if (userId in userToTable) {
                throw AlreadySeatedException("이미 대기열에 있는 사용자입니다: userId=$userId")
            }
            val queue = tableQueues.getOrPut(tableId.value) { mutableListOf() }
            queue += Entry(userId, buyIn, postBlindImmediately)
            userToTable[userId] = tableId.value
            queue.size
        }

    override fun peekHead(tableId: TableId): JoinQueueEntry? = synchronized(lock) {
        tableQueues[tableId.value]?.firstOrNull()?.toView()
    }

    override fun removeByUserId(userId: Long): TableId? = synchronized(lock) {
        val tableIdValue = userToTable.remove(userId) ?: return@synchronized null
        tableQueues[tableIdValue]?.removeIf { it.userId == userId }
        TableId(tableIdValue)
    }

    override fun entriesOf(tableId: TableId): List<JoinQueueEntry> = synchronized(lock) {
        tableQueues[tableId.value]?.map { it.toView() } ?: emptyList()
    }

    override fun isQueued(userId: Long): Boolean = synchronized(lock) { userId in userToTable }
}
