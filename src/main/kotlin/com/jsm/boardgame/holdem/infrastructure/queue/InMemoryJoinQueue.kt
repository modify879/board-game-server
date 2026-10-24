package com.jsm.boardgame.holdem.infrastructure.queue

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.exception.JoinQueueFullException
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.QueuePosition
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [JoinQueue] 의 인메모리 구현. 길이 상한([maxLength])은 메모리를 지키는 인프라 한계라 여기서 검사한다 —
 * 중복 검사와 같은 락 안이라 동시 요청에도 상한을 넘지 않는다. 단일 인스턴스 배포를 전제한다 — 여러 인스턴스면 각자 자기
 * 대기열만 보게 된다(TableViewSequence·타이머들과 같은 전제). 재시작하면 비워지지만 좌석 배정
 * 전이라 안전하다([JoinQueue] KDoc 참고).
 *
 * ponytail: 락 하나로 모든 테이블을 직렬화한다 — 대기열 크기는 사람 수 정도로 작고, 오래 걸리는
 * 작업(지갑 이체·DB 저장)은 이 락 밖(AdmitJoinRequestService 의 REQUIRES_NEW 트랜잭션)에서
 * 일어나므로 경합이 오래 붙들리지 않는다. 테이블별 트래픽이 실제로 이 락에서 병목이 되면
 * 테이블별 락으로 바꾼다.
 */
@Component
class InMemoryJoinQueue(
    @Value("\${app.holdem.join-queue-max-length}") private val maxLength: Int,
) : JoinQueue {
    private data class Entry(val userId: UserId, val postBlindImmediately: Boolean, val buyIn: Chips) {
        fun toView() = JoinQueueEntry(userId, postBlindImmediately, buyIn)
    }

    private val lock = Any()
    private val tableQueues = mutableMapOf<TableId, MutableList<Entry>>()
    private val userToTable = mutableMapOf<UserId, TableId>()

    override fun enqueue(tableId: TableId, userId: UserId, postBlindImmediately: Boolean, buyIn: Chips): Int =
        synchronized(lock) {
            if (userId in userToTable) {
                throw AlreadySeatedException("이미 대기열에 있는 사용자입니다: userId=$userId")
            }
            val queue = tableQueues.getOrPut(tableId) { mutableListOf() }
            if (queue.size >= maxLength) {
                throw JoinQueueFullException("참가 대기열이 가득 찼습니다: tableId=$tableId, max=$maxLength")
            }
            queue += Entry(userId, postBlindImmediately, buyIn)
            userToTable[userId] = tableId
            queue.size
        }

    override fun peekHead(tableId: TableId): JoinQueueEntry? = synchronized(lock) {
        tableQueues[tableId]?.firstOrNull()?.toView()
    }

    override fun removeByUserId(userId: UserId): TableId? = synchronized(lock) {
        val tableId = userToTable.remove(userId) ?: return@synchronized null
        tableQueues[tableId]?.removeIf { it.userId == userId }
        tableId
    }

    override fun entriesOf(tableId: TableId): List<JoinQueueEntry> = synchronized(lock) {
        tableQueues[tableId]?.map { it.toView() } ?: emptyList()
    }

    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) = synchronized(lock) {
        tableQueues.remove(event.tableId)?.forEach { userToTable.remove(it.userId) }
        Unit
    }

    override fun isQueued(userId: UserId): Boolean = synchronized(lock) { userId in userToTable }

    override fun tableOf(userId: UserId): TableId? = synchronized(lock) { userToTable[userId] }

    override fun positionOf(userId: UserId): QueuePosition? = synchronized(lock) {
        val tableId = userToTable[userId] ?: return@synchronized null
        val index = tableQueues[tableId]?.indexOfFirst { it.userId == userId } ?: -1
        if (index < 0) null else QueuePosition(tableId, index + 1)
    }
}
