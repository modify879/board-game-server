package com.jsm.boardgame.holdem.infrastructure.executor

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.TableId
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [TableExecutor] 의 인메모리 구현. 단일 인스턴스 배포를 전제한다(InMemoryJoinQueue·
 * TableViewSequence·타이머들과 같은 전제). 테이블마다 전용 가상 스레드를 하나씩 두고, 그
 * 테이블의 상태를 바꾸는 명령은 전부 그 스레드로 넘겨 실행한다 — 한 스레드에서만 도니
 * 같은 테이블에 대한 동시 수정이 애초에 생기지 않는다(`@Version` 낙관적 락이 하던 일을
 * 직렬화로 대신한다).
 *
 * 재진입은 [ThreadLocal] 로 감지한다: [call] 안에서 같은 테이블에 대해 다시 [call] 하면
 * 이미 그 테이블 전용 스레드 위이므로, 스레드풀에 새로 제출해 `Future.get()` 으로 기다리면
 * 자기 자신을 기다리는 데드락이 된다 — 그 자리에서 바로 실행해 피한다.
 */
@Component
class SerialTableExecutor : TableExecutor {

    private val executors = ConcurrentHashMap<Long, ExecutorService>()
    private val currentTableId = ThreadLocal<Long?>()

    override fun <T> call(tableId: TableId, task: () -> T): T {
        if (currentTableId.get() == tableId.value) {
            return task()
        }
        val future = submit(tableId, Callable {
            currentTableId.set(tableId.value)
            try {
                task()
            } finally {
                currentTableId.remove()
            }
        })
        // ponytail: 호출자 타임아웃을 일부러 두지 않는다 — 타임아웃이 나도 task 는 계속 돌아 클라이언트에
        // 실패를 알린 뒤에 커밋될 수 있다. 상한은 application.yaml 의 DB lock_timeout/statement_timeout 이다.
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun post(tableId: TableId, task: () -> Unit) {
        submit(tableId, Callable {
            currentTableId.set(tableId.value)
            try {
                task()
            } catch (e: Exception) {
                log.error("post 로 넘긴 작업이 실패했습니다: tableId={}", tableId.value, e)
            } finally {
                currentTableId.remove()
            }
        })
    }

    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        executors.remove(event.tableId.value)?.shutdown()
    }

    /** [remove] 직전에 옛 실행기를 쥔 호출자가 종료된 실행기에 제출하면 거부된다 — 새 실행기로 한 번만 다시 낸다. */
    private fun <T> submit(tableId: TableId, callable: Callable<T>): Future<T> =
        try {
            executorOf(tableId).submit(callable)
        } catch (e: RejectedExecutionException) {
            executorOf(tableId).submit(callable)
        }

    private fun executorOf(tableId: TableId): ExecutorService =
        executors.computeIfAbsent(tableId.value) {
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("holdem-table-$it").factory())
        }

    @PreDestroy
    fun shutdown() {
        executors.values.forEach { it.shutdown() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SerialTableExecutor::class.java)
    }
}
