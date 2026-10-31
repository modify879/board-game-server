package com.jsm.boardgame.holdem.infrastructure.memory

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.FoldWinnerCards
import com.jsm.boardgame.holdem.application.port.FoldWinnerCards.FoldWinner
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ConcurrentHashMap

// ponytail: 인메모리라 재시작하면 사라진다 — 재시작 직후 그 핸드의 승자는 보여 줄 수 없을 뿐이다. 문제가 되면 DB 에 저장한다.
@Component
class InMemoryFoldWinnerCards : FoldWinnerCards {
    private val winners = ConcurrentHashMap<TableId, FoldWinner>()

    // 트랜잭션 안이면 커밋 뒤에 반영한다 — 롤백된 정산의 승자 카드가 메모리에 남지 않게. 읽기는 즉시.
    override fun save(tableId: TableId, winner: FoldWinner) = afterCommit { winners[tableId] = winner }
    override fun find(tableId: TableId): FoldWinner? = winners[tableId]
    override fun remove(tableId: TableId) = afterCommit { winners.remove(tableId) }

    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = action()
            })
        } else {
            action()
        }
    }

    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        winners.remove(event.tableId)
    }
}
