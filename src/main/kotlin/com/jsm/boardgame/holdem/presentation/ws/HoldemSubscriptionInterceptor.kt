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
 * 홀덤 테이블 목적지(HoldemDestinations 가 정의하는 /topic, /user/queue 경로)의 SUBSCRIBE 를 인가한다.
 * 심플 브로커(SimpleBroker)는 목적지 단위 인가를 하지 않는다 — 이 인터셉터가 없으면 SUBSCRIBE 프레임이
 * 아무 검증 없이 브로커까지 간다.
 *
 * 공개 토픽(/topic/tables/{id})은 인증만 되면 누구나 구독할 수 있다 — 관전(자리가 없는 사용자가
 * 테이블을 보는 것)이 이 구독 하나로 성립한다. 착석 여부를 보지 않는 이유는 페이로드
 * 타입(TablePublicView)에 홀카드를 담을 필드가 아예 없어서다(규칙 6) — 관전자가 봐도 셀 정보가
 * 없다. 원래 이 인터셉터가 좌석으로 공개 토픽까지 막았던 건 관전이라는 개념이 없던 시절
 * "어차피 남의 테이블 정보는 안 보여준다"는 방어였다 — 이제는 페이로드 타입 자체가 그 방어를 대신한다.
 *
 * 개인 큐(/user/queue/tables/{id})는 여전히 좌석에 묶인다 — SeatPrivateView 는 자기 홀카드를
 * 담으므로, 그 테이블에 앉은 사용자만 구독할 수 있어야 한다.
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

        // 공개 토픽은 인증만으로 충분하다 — 관전. 좌석 검사는 개인 큐에만 남긴다.
        if (!HoldemDestinations.isPrivateQueue(destination)) return message

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
