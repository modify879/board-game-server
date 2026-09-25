package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.presentation.ws.payload.SeatPrivateView
import com.jsm.boardgame.holdem.presentation.ws.payload.privateViewOf
import com.jsm.boardgame.holdem.presentation.ws.payload.publicViewOf
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 핸드 상태 변화를 좌석별로 내보낸다. 서비스가 올린 [HandBroadcastRequested] 를 커밋 후에 받아 전송한다.
 * 뷰는 WebSocket 으로 나가는 직렬화 타입이라 presentation 에 둔다.
 */
@Component
class HandBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
    private val userRegistry: SimpUserRegistry,
    private val showdownStore: ShowdownStore,
) {

    /**
     * @TransactionalEventListener 의 기본 phase 는 AFTER_COMMIT 이다. HandBroadcastRequested 의
     * KDoc 참고 — 커밋 전에 내보내면 롤백 시 클라이언트만 앞서가는 상태를 보게 된다.
     */
    @TransactionalEventListener
    fun on(event: HandBroadcastRequested) {
        publish(event.tableId, event.table, event.hand)
    }

    fun publish(tableId: TableId, table: HoldemTable, hand: Hand?) {
        // 진행 중 핸드가 없어도(hand == null) 쇼다운 공개 선택 창이 열려 있으면 그 핸드로 공개 뷰를
        // 채운다 — 안 그러면 창이 열린 동안의 착석·기립 브로드캐스트가 결과와 선택 대기 표시를 지운다.
        // 개인 뷰 전송은 아래에서 원래 hand(null 이면 아무에게도 안 보낸다) 그대로 쓴다.
        val publicHand = hand ?: showdownStore.find(tableId)?.hand
        messagingTemplate.convertAndSend(
            HoldemDestinations.publicTopicOf(tableId.value),
            publicViewOf(tableId, table, publicHand),
        )

        hand ?: return
        for (seatNo in hand.seatNos) {
            val userId = table.seatAt(seatNo)?.userId ?: continue
            sendPrivate(tableId, userId, privateViewOf(tableId, seatNo, hand))
        }
    }

    /**
     * convertAndSendToUser 는 대상 세션이 없으면 예외도 로그도 없이 메시지를 조용히 버린다.
     * 포커에서 "당신 차례" 알림이 이렇게 증발하면 유저는 자기 차례인 줄 몰라 타임아웃 자동
     * 폴드로 돈을 잃는다 — 그래서 세션 유무를 미리 확인해 WARN 을 남긴다.
     */
    private fun sendPrivate(tableId: TableId, userId: Long, view: SeatPrivateView) {
        if (userRegistry.getUser(userId.toString()) == null) {
            log.warn("private view dropped: no active session for userId={}, tableId={}", userId, tableId.value)
        }
        messagingTemplate.convertAndSendToUser(userId.toString(), HoldemDestinations.privateQueueSendTargetOf(tableId.value), view)
    }

    companion object {
        private val log = LoggerFactory.getLogger(HandBroadcaster::class.java)
    }
}
