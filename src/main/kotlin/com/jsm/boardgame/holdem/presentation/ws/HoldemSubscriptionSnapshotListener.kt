package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.port.TurnCountdown
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.presentation.ws.payload.JoinQueueView
import com.jsm.boardgame.holdem.presentation.ws.payload.TableClosedView
import com.jsm.boardgame.holdem.presentation.ws.payload.privateViewOf
import com.jsm.boardgame.holdem.presentation.ws.payload.publicViewOf
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.simp.user.SimpUserRegistry
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
 *  - join-queue(`/user/queue/holdem/join-queue`)도 개인 큐라 `UserDestinationMessageHandler` 가 등록을
 *    끝낸 뒤에만 스냅샷을 보낸다. 같은 사용자의 다른 탭에는 보내지 않도록 구독한 세션에만 보낸다.
 * 어느 것도 아닌 핸들러(예: `/app` 을 보는 `SimpAnnotationMethodMessageHandler`)가
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
    private val lastHandResults: LastHandResultStore,
    // 이 빈이 HoldemWebSocketConfig(WebSocketMessageBrokerConfigurer)의 생성자 인자라
    // DelegatingWebSocketMessageBrokerConfiguration 이 자기 configurer 목록을 채우는 도중에
    // 만들어진다 — 그 시점에 SimpMessagingTemplate(brokerMessagingTemplate 빈)을 즉시 찾으면
    // 그 빈을 만드는 설정 클래스 자신이 아직 생성 중이라 순환 참조로 뜬다. @Lazy 로 실제 첫
    // 호출 시점까지 미룬다.
    @Lazy private val messagingTemplate: SimpMessagingTemplate,
    private val sequence: TableViewSequence,
    private val nextHandCountdown: NextHandCountdown,
    private val turnCountdown: TurnCountdown,
    private val joinQueue: JoinQueue,
    private val tableExecutor: TableExecutor,
    private val tableQuery: HoldemTableQueryRepository,
    // messagingTemplate 와 같은 빈 순환 이유(위 주석)로 @Lazy.
    @Lazy private val userRegistry: SimpUserRegistry,
) : ExecutorChannelInterceptor {

    override fun afterMessageHandled(message: Message<*>, channel: MessageChannel, handler: MessageHandler, ex: Exception?) {
        if (ex != null) return

        val accessor = StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.SUBSCRIBE) return

        val destination = accessor.destination ?: return
        val userId = accessor.user?.name?.toLongOrNull()?.let(::UserId) ?: return
        if (destination == HoldemDestinations.joinQueueDestination()) {
            if (handler !is UserDestinationMessageHandler) return
            sendJoinQueueSnapshot(userId, accessor.sessionId)
            return
        }
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

        val tableId = TableId(rawTableId)

        // 상태를 읽기 전에 seq 를 먼저 읽는다(증가시키지 않는다) — 증가시키면, 지금 커밋
        // 직전인 트랜잭션이 곧 내보낼 더 새로운 상태가 여기서 먼저 딴 더 낮은 seq 로 나가버려
        // 클라이언트가 그 갱신을 old 로 오판해 버린다.
        val seq = sequence.current(tableId)
        val table = tables.findById(tableId) ?: run {
            // 닫혔거나 없는 테이블이다. 닫힘 알림을 놓친 클라이언트가 재구독하면 빈 화면에 영원히
            // 머무르므로 알림을 다시 보낸다. 구독을 ERROR 프레임으로 거부하면 STOMP 세션 전체가
            // 끊겨 좌석·연결 타이머가 흔들리니 거부하지 않는다. 개인 큐는 인터셉터가 이미 막았다.
            if (!HoldemDestinations.isPrivateQueue(destination)) {
                messagingTemplate.convertAndSend(HoldemDestinations.publicTopicOf(rawTableId), TableClosedView(rawTableId))
            }
            return
        }
        val hand = handStore.find(tableId)
        // 테이블 토픽 스냅샷은 애그리거트가 필요해 저장소 포트로 읽는다(join-queue 스냅샷은 좌석 위치만 필요해 조회 포트를 쓴다).
        val lastResult = if (hand == null) lastHandResults.find(tableId) else null

        messagingTemplate.convertAndSend(
            HoldemDestinations.publicTopicOf(rawTableId),
            publicViewOf(tableId, table, hand, lastResult, seq, nextHandCountdown.remaining(tableId), turnCountdown.remaining(tableId)),
        )

        // 관전자(좌석 없음)는 여기서 끝난다 — 개인 뷰는 그 사용자가 이 테이블에 착석해 있을 때만 나간다.
        val seat = table.seatOf(userId) ?: return
        if (hand != null && seat.seatNo in hand.seatNos) {
            messagingTemplate.convertAndSendToUser(
                userId.value.toString(),
                HoldemDestinations.privateQueueSendTargetOf(rawTableId),
                privateViewOf(tableId, seat.seatNo, hand, seq),
            )
        }
    }

    /**
     * join-queue 채널 스냅샷. SUBSCRIBE 와 착석 REST 요청의 도착 순서가 보장되지 않고 심플 브로커는
     * SUBSCRIBE 에 RECEIPT 를 주지 않아, 구독 전에 나간 POSITION/SEATED 는 유실된다 — 지금 상태를 다시 보낸다.
     * DROPPED 는 저장된 상태가 없어 재전송할 수 없다. 오래 안 오면 클라이언트가 `GET /api/holdem/me/seat`
     * (MySeatQueryService)로 확인한다.
     *
     * 대기 중이든 착석 중이든 관련 테이블을 먼저 정하고, 읽기+전송을 그 테이블 실행기에서 한다 — 입장 처리
     * (notifySeated)·StandUp 이 같은 스레드에서 돌아 오래된 POSITION/SEATED 가 실시간 상태 뒤에 나가거나
     * 이미 일어난 SEATED 가 중복되는 일이 없다. 인바운드 스레드는 막지 않는다(post).
     * 실행기 안에서 다시 읽어 다른 테이블로 옮겨졌으면 한 번만 그 테이블로 다시 올린다. 둘 다 아니면 보내지 않는다.
     */
    private fun sendJoinQueueSnapshot(userId: UserId, sessionId: String?, retry: Boolean = true) {
        val table = currentSnapshot(userId)?.first ?: return
        tableExecutor.post(table) {
            val (current, view) = currentSnapshot(userId) ?: return@post
            when {
                current == table -> sendToSession(userId, sessionId, view)
                retry -> sendJoinQueueSnapshot(userId, sessionId, retry = false)
                else -> log.debug("스냅샷 대상이 다시 옮겨가 보내지 않는다 userId={}", userId.value)
            }
        }
    }

    /** 사용자가 지금 있는 테이블과 보낼 뷰. 대기 중이면 POSITION, 아니면 착석 중이면 SEATED, 둘 다 아니면 null. */
    private fun currentSnapshot(userId: UserId): Pair<TableId, JoinQueueView>? {
        joinQueue.positionOf(userId)?.let { return it.tableId to JoinQueueView.Position(it.tableId.value, it.position) }
        return tableQuery.findSeatOf(userId.value)?.let { TableId(it.tableId) to JoinQueueView.Seated(it.tableId, it.seatNo) }
    }

    /**
     * 구독한 세션에만 보낸다 — DefaultUserDestinationResolver 가 헤더의 sessionId 로 대상 세션을 좁힌다.
     * websocket.md 의 "WARN 후 그래도 보낸다" 와 달리 세션이 레지스트리에 없으면 보내지 않는다: 대상 세션이 없으면
     * 리졸버가 그 사용자의 모든 세션으로 폴백해 다른 탭에 뿌리게 되고, 이 스냅샷은 구독자에게만 의미가 있다.
     */
    private fun sendToSession(userId: UserId, sessionId: String?, view: JoinQueueView) {
        val name = userId.value.toString()
        if (sessionId == null || userRegistry.getUser(name)?.getSession(sessionId) == null) {
            log.warn("join-queue 스냅샷 생략: 구독 세션이 없다 userId={} sessionId={}", name, sessionId)
            return
        }
        val headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE).apply {
            this.sessionId = sessionId
            setLeaveMutable(true)
        }
        messagingTemplate.convertAndSendToUser(name, HoldemDestinations.joinQueueSendTargetOf(), view, headers.messageHeaders)
    }

    private companion object {
        val log = LoggerFactory.getLogger(HoldemSubscriptionSnapshotListener::class.java)
    }
}
