package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.presentation.ws.payload.TableClosedView
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 닫힘 커밋 후에 구독자에게 한 번 알린다(규칙 6: 커밋 후 전송).
 *
 * 최선 노력이다([HandBroadcaster.publish] 와 같다) — 이미 커밋된 뒤라 예외를 올리지 않고 잡아 ERROR 로 남긴다.
 * `convertAndSend` 가 동기로 던지는 것은 메시지 변환 실패와 인바운드 채널 전송 실패뿐이고, SimpleBroker 의
 * 구독자 전달은 브로커 채널 실행기에서 비동기로 일어나 여기서 보이지 않는다. 재시도하지 않는다 —
 * 놓친 구독자는 재구독하면 스냅샷 리스너가 같은 알림을 다시 보낸다(회복 경로).
 */
@Component
class TableClosedBroadcaster(private val messagingTemplate: SimpMessagingTemplate) {

    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        val tableId = event.tableId.value
        try {
            messagingTemplate.convertAndSend(HoldemDestinations.publicTopicOf(tableId), TableClosedView(tableId))
        } catch (e: Exception) {
            log.error("table closed notice failed: tableId={}", tableId, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TableClosedBroadcaster::class.java)
    }
}
