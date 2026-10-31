package com.jsm.boardgame.holdem.infrastructure.job

import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandHistoryJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * 하우스 룰 11 — 핸드 히스토리는 `hand-history-retention` 동안만 둔다. 하루에 한 번 그보다 오래된 핸드를 지운다(자식 행은 FK cascade).
 * 기동 직후 한 번, 이후 `hand-history-purge-interval` 간격. 고정 지연(`scheduleWithFixedDelay`)은 `SimpleAsyncTaskScheduler` 가 모든 타이머가 공유하는
 * 스케줄러 스레드 하나에서 돌려 DELETE 가 타이머를 막으므로, 발화마다 새 가상 스레드를 쓰는 고정 주기(`scheduleAtFixedRate`)로 건다.
 * 테이블 스레드와 무관해 `TableExecutor` 를 거치지 않는다.
 */
@Component
class HandHistoryPurger(
    private val taskScheduler: TaskScheduler,
    private val hands: HandHistoryJpaRepository,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @Value("\${app.holdem.hand-history-retention}") private val retention: Duration,
    @Value("\${app.holdem.hand-history-purge-interval}") private val purgeInterval: Duration,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        taskScheduler.scheduleAtFixedRate(::purge, purgeInterval)
    }

    fun purge() {
        try {
            val cutoff = clock.instant().minus(retention)
            var deleted = 0
            // 배치마다 트랜잭션을 끊어 긴 DELETE 하나가 락을 오래 쥐지 않게 한다. 자식 행은 FK 의 ON DELETE CASCADE 가 지운다.
            while (true) {
                val n = tx.execute {
                    val batch = hands.findAllByEndedAtBefore(cutoff, PageRequest.of(0, BATCH_SIZE))
                    hands.deleteAllInBatch(batch)
                    batch.size
                }
                if (n == 0) break
                deleted += n
            }
            if (deleted > 0) log.info("hand history purge: deleted={}", deleted)
        } catch (e: Exception) {
            log.error("hand history purge 실패", e)
        }
    }

    companion object {
        private const val BATCH_SIZE = 500
        private val log = LoggerFactory.getLogger(HandHistoryPurger::class.java)
    }
}
