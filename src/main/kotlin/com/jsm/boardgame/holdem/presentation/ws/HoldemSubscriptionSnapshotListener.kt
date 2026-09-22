package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.presentation.ws.payload.privateViewOf
import com.jsm.boardgame.holdem.presentation.ws.payload.publicViewOf
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionSubscribeEvent

/**
 * 재접속(또는 최초 구독) 직후, 그 시점의 상태를 1회 밀어준다. 브로드캐스트는 상태가 바뀔 때마다
 * 나가는 증분이라, 막 구독을 연 클라이언트는 그때까지 쌓인 상태를 전혀 모른다 — 구독이 성립하는
 * 순간 스냅샷을 하나 보내야 화면을 채울 수 있다.
 *
 * 구독 인가는 HoldemSubscriptionInterceptor(SUBSCRIBE 프레임의 ChannelInterceptor)가 이 이벤트가
 * 발행되기 전에 이미 끝냈으므로 여기서 다시 검사하지 않는다.
 *
 * StompHeaderAccessor.wrap(message) 는 여기서 헤더를 읽기만 하고 쓰지 않으므로 안전하다
 * (쓰려면 MessageHeaderAccessor.getAccessor(...) 를 써야 한다).
 */
@Component
class HoldemSubscriptionSnapshotListener(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val messagingTemplate: SimpMessagingTemplate,
) {

    @EventListener
    fun on(event: SessionSubscribeEvent) {
        val destination = StompHeaderAccessor.wrap(event.message).destination ?: return
        val rawTableId = HoldemDestinations.tableIdOf(destination) ?: return
        val userId = event.user?.name?.toLongOrNull() ?: return
        val tableId = TableId(rawTableId)

        val table = tables.findById(tableId) ?: return
        val hand = handStore.find(tableId)

        messagingTemplate.convertAndSend(HoldemDestinations.publicTopicOf(rawTableId), publicViewOf(tableId, table, hand))

        val seat = table.seatOf(userId) ?: return
        if (hand != null && seat.seatNo in hand.seatNos) {
            messagingTemplate.convertAndSendToUser(
                userId.toString(),
                HoldemDestinations.privateQueueSendTargetOf(rawTableId),
                privateViewOf(tableId, seat.seatNo, hand),
            )
        }
    }
}
