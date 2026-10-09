package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.presentation.ws.payload.TableClosedView
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/** 닫힘 커밋 후에 구독자에게 한 번 알린다(규칙 6: 커밋 후 전송). */
@Component
class TableClosedBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        // 이미 커밋된 뒤라 예외를 올리지 않는다 — 유실돼도 클라이언트가 재구독하면 스냅샷 리스너가 다시 보낸다.
        try {
            messagingTemplate.convertAndSend(HoldemDestinations.publicTopicOf(event.tableId.value), TableClosedView(event.tableId.value))
        } catch (e: Exception) {
            log.error("table closed notice failed: tableId={}", event.tableId.value, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TableClosedBroadcaster::class.java)
    }
}
