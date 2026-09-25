package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
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
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 핸드 상태 변화를 좌석별로 내보낸다. 서비스가 올린 [HandBroadcastRequested] 를 받아 전송한다.
 * 뷰는 WebSocket 으로 나가는 직렬화 타입이라 presentation 에 둔다.
 */
@Component
class HandBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
    private val userRegistry: SimpUserRegistry,
    private val sequence: TableViewSequence,
) {

    /**
     * BEFORE_COMMIT 에서 순번을 딴다. 같은 행(테이블·진행 중 핸드)을 바꾸는 두 트랜잭션은
     * saveAndFlush 의 행 락 때문에 나중 트랜잭션이 앞 트랜잭션의 커밋 뒤에야 여기 도달한다 —
     * 즉 순번을 따는 순서 = 커밋 순서다. clientOutboundChannel 이 스레드풀이라 전송 순서가
     * 뒤바뀌어도, 클라이언트가 seq 로 옛 메시지를 걸러내면 커밋 순서가 그대로 보존된다.
     *
     * 실제 전송은 registerSynchronization 의 afterCommit 으로 미룬다 — HandBroadcastRequested 의
     * KDoc 참고: 커밋 전에 내보내면 롤백 시 클라이언트만 앞서가는 상태를 보게 된다. 롤백되면
     * 이 동기화 자체가 버려지므로 아무것도 나가지 않는다(CLAUDE.md 규칙 6).
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun on(event: HandBroadcastRequested) {
        val seq = sequence.next(event.tableId)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                publish(event.tableId, event.table, event.hand, seq)
            }
        })
    }

    fun publish(tableId: TableId, table: HoldemTable, hand: Hand?, seq: Long) {
        messagingTemplate.convertAndSend(
            HoldemDestinations.publicTopicOf(tableId.value),
            publicViewOf(tableId, table, hand, seq),
        )

        hand ?: return
        for (seatNo in hand.seatNos) {
            val userId = table.seatAt(seatNo)?.userId ?: continue
            sendPrivate(tableId, userId, privateViewOf(tableId, seatNo, hand, seq))
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
