package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.common.error.PermissionDeniedException
import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.stereotype.Component

/**
 * 홀덤 테이블 목적지(HoldemDestinations 가 정의하는 /topic, /user/queue 경로)의 SUBSCRIBE 를
 * 인가한다. 심플 브로커(SimpleBroker)는 목적지 단위 인가를 하지 않는다 — 이 인터셉터가
 * 없으면 앉지 않은 테이블의 /topic 을 아무나 구독해 다른 좌석의 공개 정보를 엿볼 수 있다.
 *
 * userId 는 CONNECT 단계에서 StompAuthenticationInterceptor 가 세팅한 인증 주체를 그대로 쓴다
 * (STOMP 세션이 CONNECT 이후 모든 프레임에 그 주체를 자동으로 실어준다).
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@Component
class HoldemSubscriptionInterceptor(
    private val holdemTableQueryRepository: HoldemTableQueryRepository,
) : ChannelInterceptor {

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val accessor = StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.SUBSCRIBE) return message

        val destination = accessor.destination ?: return message
        val tableId = HoldemDestinations.tableIdOf(destination) ?: return message

        val userId = accessor.user?.name?.toLongOrNull()
            ?: run {
                log.warn("subscribe rejected: no authenticated user, destination={}", destination)
                throw AuthenticationRequiredException("구독하려면 인증이 필요하다")
            }

        val seat = holdemTableQueryRepository.findSeatOf(userId)
        if (seat == null || seat.tableId != tableId) {
            log.warn("subscribe rejected: userId={} is not seated at tableId={}", userId, tableId)
            throw PermissionDeniedException("앉지 않은 테이블은 구독할 수 없다")
        }

        return message
    }

    companion object {
        private val log = LoggerFactory.getLogger(HoldemSubscriptionInterceptor::class.java)
    }
}
