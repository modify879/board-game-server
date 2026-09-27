package com.jsm.boardgame.holdem.infrastructure.executor

import com.jsm.boardgame.holdem.domain.model.TableId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SerialTableExecutorTestException(message: String) : RuntimeException(message)

class SerialTableExecutorTest {

    @Test
    fun `같은 테이블의 작업은 제출 순서대로 직렬 실행되고 동시에 둘 이상 돌지 않는다`() {
        val executor = SerialTableExecutor()
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
        val executor = SerialTableExecutor()
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
        val executor = SerialTableExecutor()
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
        val executor = SerialTableExecutor()
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
        val executor = SerialTableExecutor()
        val tableId = TableId(1L)
        val done = CountDownLatch(1)

        executor.post(tableId) {
            Thread.sleep(150)
            done.countDown()
        }

        assertEquals(1, done.count)
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
}
