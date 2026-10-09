package com.jsm.boardgame.holdem.infrastructure.executor

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
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
class SerialTableExecutor(
    private val tables: HoldemTableRepository,
) : TableExecutor {

    private val executors = ConcurrentHashMap<TableId, ExecutorService?>() // 값이 null 인 항목은 저장되지 않는다 — computeIfAbsent 가 null 을 돌려줄 수 있게 하는 타입일 뿐
    private val currentTableId = ThreadLocal<TableId?>()

    override fun <T> call(tableId: TableId, task: () -> T): T {
        if (currentTableId.get() == tableId) {
            return task()
        }
        val future = submit(tableId, Callable {
            currentTableId.set(tableId)
            try {
                task()
            } finally {
                currentTableId.remove()
            }
        }) ?: return task()
        // ponytail: 호출자 타임아웃을 일부러 두지 않는다 — 타임아웃이 나도 task 는 계속 돌아 클라이언트에
        // 실패를 알린 뒤에 커밋될 수 있다. 상한은 application.yaml 의 DB lock_timeout/statement_timeout 이다.
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun post(tableId: TableId, task: () -> Unit) {
        val run = {
            try {
                task()
            } catch (e: Exception) {
                log.error("post 로 넘긴 작업이 실패했습니다: tableId={}", tableId.value, e)
            }
        }
        submit(tableId, Callable {
            currentTableId.set(tableId)
            try {
                run()
            } finally {
                currentTableId.remove()
            }
        }) ?: run()
    }

    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        executors.remove(event.tableId)?.shutdown()
    }

    /**
     * [remove] 직전에 옛 실행기를 쥔 호출자가 종료된 실행기에 제출하면 거부된다 — 새 실행기로 한 번만 다시 낸다.
     * 실행기가 없는 테이블이면 null — 호출자가 그 자리에서 실행한다.
     */
    private fun <T> submit(tableId: TableId, callable: Callable<T>): Future<T>? =
        try {
            executorOf(tableId)?.submit(callable)
        } catch (e: RejectedExecutionException) {
            executorOf(tableId)?.submit(callable)
        }

    /**
     * 없는 테이블(없거나 닫힘)에는 실행기를 만들지 않는다 — 직렬화할 상태가 없고 작업이 알아서 TABLE_NOT_FOUND 나
     * no-op 이 된다. 닫힘은 그 테이블의 실행기 위에서 돌아 커밋 뒤에야 실행기가 지워지므로, 지워진 뒤의 생성 시도는
     * 항상 커밋된 닫힘을 읽는다(경합 없음). 이미 있는 실행기는 DB 를 안 건드린다.
     * 있는 실행기는 get 으로 바로 돌려줘 생성 경로(DB 조회를 하며 빈 락을 쥔다)에 들어가지 않는다.
     */
    private fun executorOf(tableId: TableId): ExecutorService? =
        executors[tableId] ?: executors.computeIfAbsent(tableId) {
            if (tables.findById(tableId) == null) null
            else Executors.newSingleThreadExecutor(Thread.ofVirtual().name("holdem-table-${tableId.value}").factory())
        }

    @PreDestroy
    fun shutdown() {
        executors.values.forEach { it?.shutdown() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SerialTableExecutor::class.java)
    }
}
