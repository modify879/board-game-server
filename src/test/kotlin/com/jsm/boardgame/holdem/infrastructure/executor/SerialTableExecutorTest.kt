package com.jsm.boardgame.holdem.infrastructure.executor

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 존재 여부만 가른다 — 기본은 모든 테이블이 있고, [absent] 에 든 id 는 없거나 닫힌 테이블이다(실제 어댑터처럼 null).
private class SerialExecutorFakeHoldemTableRepository : HoldemTableRepository {
    val absent = mutableSetOf<TableId>()

    override fun findById(id: TableId): HoldemTable? = if (id in absent) null else HoldemTable.create("t")
    override fun findByUserId(userId: Long): HoldemTable? = null
    override fun findAllSeatedUserIds(): List<Long> = emptyList()
    override fun findAllTableIds(): List<TableId> = emptyList()
    override fun save(table: HoldemTable): HoldemTable = table
}

private class SerialTableExecutorTestException(message: String) : RuntimeException(message)

class SerialTableExecutorTest {

    @Test
    fun `같은 테이블의 작업은 제출 순서대로 직렬 실행되고 동시에 둘 이상 돌지 않는다`() {
        val executor = SerialTableExecutor(SerialExecutorFakeHoldemTableRepository())
        val tableId = TableId(1L)
        val taskCount = 5
        val done = CountDownLatch(taskCount)
        val order = mutableListOf<Int>()
        var inProgress = 0
        var maxConcurrent = 0
        val lock = Any()

        for (i in 0 until taskCount) {
            executor.post(tableId) {
                synchronized(lock) {
                    inProgress++
                    maxConcurrent = maxOf(maxConcurrent, inProgress)
                }
                Thread.sleep(20)
                synchronized(lock) {
                    order += i
                    inProgress--
                }
                done.countDown()
            }
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals((0 until taskCount).toList(), order)
        assertEquals(1, maxConcurrent)
    }

    @Test
    fun `서로 다른 테이블의 작업은 서로를 기다리지 않고 동시에 실행된다`() {
        val executor = SerialTableExecutor(SerialExecutorFakeHoldemTableRepository())
        val tableA = TableId(1L)
        val tableB = TableId(2L)
        val startedA = CountDownLatch(1)
        val startedB = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val releaseB = CountDownLatch(1)

        executor.post(tableA) {
            startedA.countDown()
            releaseA.await(5, TimeUnit.SECONDS)
        }
        executor.post(tableB) {
            startedB.countDown()
            releaseB.await(5, TimeUnit.SECONDS)
        }

        assertTrue(startedA.await(1, TimeUnit.SECONDS))
        assertTrue(startedB.await(1, TimeUnit.SECONDS))

        releaseA.countDown()
        releaseB.countDown()
    }

    @Test
    fun `call 은 task 가 던진 예외를 감싸지 않고 원래 타입 그대로 다시 던진다`() {
        val executor = SerialTableExecutor(SerialExecutorFakeHoldemTableRepository())
        val tableId = TableId(1L)

        val exception = assertFailsWith<SerialTableExecutorTestException> {
            executor.call(tableId) {
                throw SerialTableExecutorTestException("boom")
            }
        }
        assertEquals("boom", exception.message)
    }

    @Test
    fun `같은 테이블 전용 스레드 위에서 재진입한 call 은 그 자리에서 실행되어 데드락에 빠지지 않는다`() {
        val executor = SerialTableExecutor(SerialExecutorFakeHoldemTableRepository())
        val tableId = TableId(1L)
        var result: Int? = null

        val t = thread {
            result = executor.call(tableId) {
                executor.call(tableId) { 42 }
            }
        }
        t.join(5_000)

        assertFalse(t.isAlive)
        assertEquals(42, result)
    }

    @Test
    fun `post 는 작업 완료를 기다리지 않고 즉시 반환한다`() {
        val executor = SerialTableExecutor(SerialExecutorFakeHoldemTableRepository())
        val tableId = TableId(1L)
        val done = CountDownLatch(1)

        executor.post(tableId) {
            Thread.sleep(150)
            done.countDown()
        }

        assertEquals(1, done.count)
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `닫힌 뒤에 다시 call 하면 실행기를 만들지 않고 호출자 스레드에서 돈다`() {
        val tables = SerialExecutorFakeHoldemTableRepository()
        val executor = SerialTableExecutor(tables)
        val tableId = TableId(1L)
        executor.call(tableId) { }

        executor.onTableClosed(TableClosed(tableId))
        tables.absent += tableId
        val after = executor.call(tableId) { Thread.currentThread() }

        assertSame(Thread.currentThread(), after)
    }

    @Test
    fun `없는 테이블의 call 과 post 는 실행기를 만들지 않고 호출자 스레드에서 돈다`() {
        val tables = SerialExecutorFakeHoldemTableRepository()
        val executor = SerialTableExecutor(tables)
        val tableId = TableId(99L)
        tables.absent += tableId
        var postThread: Thread? = null

        val callThread = executor.call(tableId) { Thread.currentThread() }
        executor.post(tableId) { postThread = Thread.currentThread() }

        assertSame(Thread.currentThread(), callThread)
        assertSame(Thread.currentThread(), postThread)
    }

    @Test
    fun `없는 테이블의 call 은 작업의 예외를 그대로 던지고 post 는 삼킨다`() {
        val tables = SerialExecutorFakeHoldemTableRepository()
        val executor = SerialTableExecutor(tables)
        val tableId = TableId(99L)
        tables.absent += tableId

        assertFailsWith<SerialTableExecutorTestException> {
            executor.call(tableId) { throw SerialTableExecutorTestException("실패") }
        }
        executor.post(tableId) { throw SerialTableExecutorTestException("실패") }
    }

    @Test
    fun `있는 테이블의 작업은 holdem-table 스레드에서 돈다`() {
        val executor = SerialTableExecutor(SerialExecutorFakeHoldemTableRepository())

        val name = executor.call(TableId(1L)) { Thread.currentThread().name }

        assertEquals("holdem-table-1", name)
    }

    @Test
    fun `테이블 닫힘은 진행 중인 작업을 끊지 않는다`() {
        val executor = SerialTableExecutor(SerialExecutorFakeHoldemTableRepository())
        val tableId = TableId(1L)

        val result = executor.call(tableId) {
            executor.onTableClosed(TableClosed(tableId))
            42
        }

        assertEquals(42, result)
    }
}
