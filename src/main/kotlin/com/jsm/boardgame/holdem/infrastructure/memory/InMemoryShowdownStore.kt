package com.jsm.boardgame.holdem.infrastructure.memory

import com.jsm.boardgame.holdem.application.port.OpenShowdown
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 쇼다운 공개 선택 창을 메모리에만 둔다(사용자 결정) — 재시작하면 사라지고, 그건 전원 MUCK 과
 * 같은 뜻이다. 진행 중 핸드([HandStore])와 달리 복구 대상이 아니다.
 *
 * save/remove 는 활성 트랜잭션이 있으면 커밋 후에 반영한다 — 정산 트랜잭션([HandSettler.settle])이
 * 낙관적 락 등으로 롤백되면, 이미 열어버린 창이 롤백된 핸드를 들고 남아 있으면 안 된다. 트랜잭션이
 * 없으면(테스트 등) 즉시 반영한다.
 */
@Component
class InMemoryShowdownStore : ShowdownStore {
    private val store = ConcurrentHashMap<Long, OpenShowdown>()

    override fun find(tableId: TableId): OpenShowdown? = store[tableId.value]

    override fun save(tableId: TableId, showdown: OpenShowdown) {
        afterCommitOrNow { store[tableId.value] = showdown }
    }

    override fun remove(tableId: TableId) {
        afterCommitOrNow { store.remove(tableId.value) }
    }

    private fun afterCommitOrNow(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }
}
