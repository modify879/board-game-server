package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.presentation.ws.payload.privateViewOf
import com.jsm.boardgame.holdem.presentation.ws.payload.publicViewOf
import org.springframework.context.annotation.Lazy
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.simp.user.UserDestinationMessageHandler
import org.springframework.messaging.support.ExecutorChannelInterceptor
import org.springframework.stereotype.Component

/**
 * 재접속(또는 최초 구독) 직후, 그 시점의 상태를 1회 밀어준다. 브로드캐스트는 상태가 바뀔 때마다
 * 나가는 증분이라, 막 구독을 연 클라이언트는 그때까지 쌓인 상태를 전혀 모른다 — 구독이 성립하는
 * 순간 스냅샷을 하나 보내야 화면을 채울 수 있다.
 *
 * `SessionSubscribeEvent`(`@EventListener`) 대신 `ExecutorChannelInterceptor.afterMessageHandled` 를
 * 쓴다. Spring 7 의 `StompSubProtocolHandler.handleMessageFromClient` 는 `clientInboundChannel.send(message)`
 * 직후 `SessionSubscribeEvent` 를 발행하는데, `clientInboundChannel` 은 실행기 채널(`ExecutorSubscribableChannel`)이라
 * `send` 가 반환되는 시점은 각 구독자(핸들러)에게 작업을 제출했다는 뜻일 뿐, 브로커가 실제로 구독을
 * 등록했다는 보장이 아니다 — 그 사이 이 리스너가 먼저 돌면 스냅샷이 조용히 유실된다.
 * `afterMessageHandled` 는 그 SUBSCRIBE 를 실제로 처리한 핸들러가 끝난 뒤에만 불리므로 등록이
 * 끝난 다음이라는 게 보장된다:
 *  - 공개 토픽(`/topic/tables/{id}`)은 `SimpleBrokerMessageHandler` 가 `clientInboundChannel` 에서
 *    직접 구독을 등록한다.
 *  - 개인 큐(`/user/queue/tables/{id}`)는 `UserDestinationMessageHandler` 가 목적지를 세션 전용
 *    경로로 번역해 `brokerChannel` 로 넘기는데, `brokerChannel` 은 `WebSocketConfig` 가 태스크
 *    실행기를 설정하지 않아 실행기가 없다(`ExecutorSubscribableChannel` 생성자 인자 없음) — 그래서
 *    같은 스레드에서 동기로 `SimpleBrokerMessageHandler` 까지 이어져, `UserDestinationMessageHandler`
 *    의 `handleMessage` 가 반환하는 시점이면 등록도 이미 끝나 있다.
 * 두 destination 모두 아닌 핸들러(예: `/app` 을 보는 `SimpAnnotationMethodMessageHandler`)가
 * 끝났을 때는 등록을 한 것이 아니므로 무시한다.
 *
 * 구독 인가는 HoldemSubscriptionInterceptor(SUBSCRIBE 프레임의 preSend 단계 ChannelInterceptor)가
 * 이 시점 이전에 이미 끝냈으므로 여기서 다시 검사하지 않는다.
 *
 * StompHeaderAccessor.wrap(message) 는 여기서 헤더를 읽기만 하고 쓰지 않으므로 안전하다
 * (쓰려면 MessageHeaderAccessor.getAccessor(...) 를 써야 한다).
 */
@Component
class HoldemSubscriptionSnapshotListener(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    // 이 빈이 HoldemWebSocketConfig(WebSocketMessageBrokerConfigurer)의 생성자 인자라
    // DelegatingWebSocketMessageBrokerConfiguration 이 자기 configurer 목록을 채우는 도중에
    // 만들어진다 — 그 시점에 SimpMessagingTemplate(brokerMessagingTemplate 빈)을 즉시 찾으면
    // 그 빈을 만드는 설정 클래스 자신이 아직 생성 중이라 순환 참조로 뜬다. @Lazy 로 실제 첫
    // 호출 시점까지 미룬다.
    @Lazy private val messagingTemplate: SimpMessagingTemplate,
    private val sequence: TableViewSequence,
) : ExecutorChannelInterceptor {

    override fun afterMessageHandled(message: Message<*>, channel: MessageChannel, handler: MessageHandler, ex: Exception?) {
        if (ex != null) return

        val accessor = StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.SUBSCRIBE) return

        val destination = accessor.destination ?: return
        val rawTableId = HoldemDestinations.tableIdOf(destination) ?: return

        // 이 핸들러가 방금 처리를 "끝냈다" 는 게 실제로 그 destination 의 구독을 등록한
        // 핸들러라는 뜻인지 확인한다 — 아니면 아직 등록 전이거나(다른 핸들러 차례) 애초에
        // 등록과 무관한 핸들러(SimpAnnotationMethodMessageHandler 등)다.
        val registeredThisDestination = if (HoldemDestinations.isPrivateQueue(destination)) {
            handler is UserDestinationMessageHandler
        } else {
            handler is SimpleBrokerMessageHandler
        }
        if (!registeredThisDestination) return

        val userId = accessor.user?.name?.toLongOrNull() ?: return
        val tableId = TableId(rawTableId)

        // 상태를 읽기 전에 seq 를 먼저 읽는다(증가시키지 않는다) — 증가시키면, 지금 커밋
        // 직전인 트랜잭션이 곧 내보낼 더 새로운 상태가 여기서 먼저 딴 더 낮은 seq 로 나가버려
        // 클라이언트가 그 갱신을 old 로 오판해 버린다.
        val seq = sequence.current(tableId)
        val table = tables.findById(tableId) ?: return
        val hand = handStore.find(tableId)

        messagingTemplate.convertAndSend(HoldemDestinations.publicTopicOf(rawTableId), publicViewOf(tableId, table, hand, seq))

        // 관전자(좌석 없음)는 여기서 끝난다 — 개인 뷰는 그 사용자가 이 테이블에 착석해 있을 때만 나간다.
        val seat = table.seatOf(userId) ?: return
        if (hand != null && seat.seatNo in hand.seatNos) {
            messagingTemplate.convertAndSendToUser(
                userId.toString(),
                HoldemDestinations.privateQueueSendTargetOf(rawTableId),
                privateViewOf(tableId, seat.seatNo, hand, seq),
            )
        }
    }
}
