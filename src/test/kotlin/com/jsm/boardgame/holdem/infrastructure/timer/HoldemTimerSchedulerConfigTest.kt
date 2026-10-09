package com.jsm.boardgame.holdem.infrastructure.timer

import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoldemTimerSchedulerConfigTest {

    private val scheduler = HoldemTimerSchedulerConfig().taskScheduler() as SimpleAsyncTaskScheduler

    private fun <T> withStarted(block: () -> T): T {
        scheduler.start()
        try {
            return block()
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `블로킹된 작업이 여럿이어도 다른 작업은 제때 실행된다`() = withStarted {
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(2)
        repeat(2) {
            scheduler.schedule({ blocked.countDown(); release.await() }, Instant.now())
        }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))

        val ran = CountDownLatch(1)
        scheduler.schedule({ ran.countDown() }, Instant.now().plusMillis(50))

        val ranInTime = ran.await(1, TimeUnit.SECONDS)
        release.countDown()
        assertTrue(ranInTime)
    }

    @Test
    fun `예약된 작업은 발화 전 남은 시간이 양수이고 cancel 하면 실행되지 않는다`() = withStarted {
        val ran = CountDownLatch(1)
        val future = scheduler.schedule({ ran.countDown() }, Instant.now().plusMillis(300))

        assertTrue(future.getDelay(TimeUnit.MILLISECONDS) > 0)
        future.cancel(false)

        assertFalse(ran.await(600, TimeUnit.MILLISECONDS))
        assertEquals(true, future.isCancelled)
    }

    // stop() 은 일시정지일 뿐이다 - 거부는 컨텍스트 종료 이벤트가 실행기를 내릴 때부터 시작된다.
    @Test
    fun `컨텍스트가 닫힌 뒤 예약하면 TaskRejectedException 이다`() {
        val context = GenericApplicationContext()
        scheduler.setApplicationContext(context)
        scheduler.start()
        scheduler.onApplicationEvent(ContextClosedEvent(context))

        assertFailsWith<TaskRejectedException> { scheduler.schedule({}, Instant.now().plusSeconds(1)) }
        scheduler.close()
    }
}
