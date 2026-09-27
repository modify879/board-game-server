package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.holdem.application.port.JoinQueueEntry
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.presentation.ws.payload.JoinQueueView
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.stereotype.Component

@Component
class JoinQueueBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
    private val userRegistry: SimpUserRegistry,
) : JoinQueueNotifier {

    override fun notifySeated(tableId: TableId, userId: Long, seatNo: Int) =
        send(userId, JoinQueueView.Seated(tableId.value, seatNo))

    override fun notifyDropped(tableId: TableId, userId: Long, errorCode: ErrorCode) =
        send(userId, JoinQueueView.Dropped(tableId.value, errorCode.code))

    override fun notifyPositions(tableId: TableId, entries: List<JoinQueueEntry>) {
        entries.forEachIndexed { index, entry -> send(entry.userId, JoinQueueView.Position(tableId.value, index + 1)) }
    }

    /** convertAndSendToUser 는 대상 세션이 없으면 예외도 로그도 없이 조용히 버린다 — HandBroadcaster 와
     *  같은 이유로 WARN 을 남기되 전송은 그대로 한다(레지스트리 경합으로 오탐 낼 수 있어, 안 보내면
     *  오히려 진짜 유실이 생긴다). */
    private fun send(userId: Long, view: JoinQueueView) {
        if (userRegistry.getUser(userId.toString()) == null) {
            log.warn("join-queue view dropped: no active session for userId={}", userId)
        }
        messagingTemplate.convertAndSendToUser(userId.toString(), HoldemDestinations.joinQueueSendTargetOf(), view)
    }

    companion object {
        private val log = LoggerFactory.getLogger(JoinQueueBroadcaster::class.java)
    }
}
