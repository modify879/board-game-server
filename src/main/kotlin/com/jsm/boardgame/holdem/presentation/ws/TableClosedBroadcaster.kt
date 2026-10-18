package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.infrastructure.timer.runRetrying
import com.jsm.boardgame.holdem.presentation.ws.payload.TableClosedView
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant

/**
 * 닫힘 커밋 후에 구독자에게 한 번 알린다(규칙 6: 커밋 후 전송).
 *
 * 이미 커밋된 뒤라 예외를 올리지 않고, 이벤트 스레드(커밋한 스레드, 아직 JDBC 커넥션을 쥐고 있다)를 막지도 않는다 —
 * 전송과 재시도 모두 스케줄러 스레드에서 한다. `convertAndSend` 가 동기로 던지는 것은 메시지 변환 실패와
 * 인바운드 채널 전송 실패(실행기 포화로 거부 등)뿐이고, SimpleBroker 의 구독자 전달은 브로커 채널 실행기에서
 * 비동기로 일어나 여기서 보이지 않는다. 앞쪽은 일시적일 수 있어 지수 백오프로 다시 시도하고, 끝내 실패하면 ERROR 를 남긴다.
 * 그래도 놓친 구독자는 재구독하면 스냅샷 리스너가 같은 알림을 다시 보낸다(보조 회복 경로).
 */
@Component
class TableClosedBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
) {
    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        val tableId = event.tableId.value
        try {
            taskScheduler.schedule({
                taskScheduler.runRetrying(
                    "닫힘 알림 전송 tableId=$tableId", clock,
                    isStale = { false },
                    onGiveUp = { log.error("table closed notice failed after retries: tableId={}", tableId, it) },
                ) {
                    messagingTemplate.convertAndSend(HoldemDestinations.publicTopicOf(tableId), TableClosedView(tableId))
                }
            }, Instant.now(clock))
        } catch (e: TaskRejectedException) {
            // 컨텍스트 종료 중이다 — 보낼 이유도 구독자도 없다
            log.debug("컨텍스트 종료 중이라 닫힘 알림을 예약하지 못했습니다: tableId={}", tableId)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TableClosedBroadcaster::class.java)
    }
}
