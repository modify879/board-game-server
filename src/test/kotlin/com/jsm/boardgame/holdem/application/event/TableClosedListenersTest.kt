package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.infrastructure.executor.SerialTableExecutor
import com.jsm.boardgame.holdem.infrastructure.queue.InMemoryJoinQueue
import com.jsm.boardgame.holdem.infrastructure.recovery.HandRecovery
import com.jsm.boardgame.holdem.infrastructure.timer.ConnectionTimer
import com.jsm.boardgame.holdem.infrastructure.timer.NextHandTimer
import com.jsm.boardgame.holdem.infrastructure.timer.RunoutTimer
import com.jsm.boardgame.holdem.infrastructure.timer.TurnTimer
import com.jsm.boardgame.holdem.presentation.ws.TableClosedBroadcaster
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import kotlin.test.Test
import kotlin.test.assertTrue

class TableClosedListenersTest {
    // 단위 테스트는 리스너 메서드를 직접 부르므로 어노테이션이 빠져도 통과한다 — 연결 자체를 여기서 못 박는다; 테이블별 상태를 새로 두면 이 목록에 추가한다.
    @Test
    fun `테이블별 인메모리 상태를 가진 컴포넌트는 모두 TableClosed 를 커밋 후 리스너로 받는다`() {
        val components = listOf(
            TurnTimer::class.java,
            NextHandTimer::class.java,
            RunoutTimer::class.java,
            ConnectionTimer::class.java,
            HandRecovery::class.java,
            InMemoryJoinQueue::class.java,
            SerialTableExecutor::class.java,
            TableClosedBroadcaster::class.java,
        )

        components.forEach { type ->
            val listens = type.declaredMethods.any { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0] == TableClosed::class.java &&
                    method.getAnnotation(TransactionalEventListener::class.java)?.phase == TransactionPhase.AFTER_COMMIT
            }
            assertTrue(listens, "${type.simpleName} 에 AFTER_COMMIT @TransactionalEventListener(TableClosed) 메서드가 없다")
        }
    }
}
