package com.jsm.boardgame.holdem.infrastructure.executor

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

    // ponytail: 테이블은 삭제되지 않으므로 executor 를 맵에서 빼지 않는다 — 테이블 수만큼만
    // 쌓이고, 그 수는 실제로 무해할 만큼 작다. 테이블이 삭제되는 날이 오면 그때 정리한다.
    private val executors = ConcurrentHashMap<Long, ExecutorService>()
    private val currentTableId = ThreadLocal<Long?>()

    override fun <T> call(tableId: TableId, task: () -> T): T {
        if (currentTableId.get() == tableId.value) {
            return task()
        }
        val future = executorOf(tableId).submit(Callable {
            currentTableId.set(tableId.value)
            try {
                task()
            } finally {
                currentTableId.remove()
            }
        })
        // ponytail: 타임아웃 없이 get() 으로 기다린다 — task 가 걸리면 이 테이블 전체가
        // 멈춘다. 지금은 감내한다(트랜잭션 자체의 타임아웃이 실질적인 상한선이다).
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun post(tableId: TableId, task: () -> Unit) {
        executorOf(tableId).submit {
            currentTableId.set(tableId.value)
            try {
                task()
            } catch (e: Exception) {
                log.error("post 로 넘긴 작업이 실패했습니다: tableId={}", tableId.value, e)
            } finally {
                currentTableId.remove()
            }
        }
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
