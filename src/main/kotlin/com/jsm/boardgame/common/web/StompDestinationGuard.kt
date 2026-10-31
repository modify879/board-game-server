package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.PermissionDeniedException
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.stereotype.Component

/**
 * convertAndSendToUser 가 세션별로 라우팅하는 실제 큐(/queue/<dest>-user<sessionId>)를 클라이언트가
 * 직접 구독하면 세션 id 만 알아도 남의 개인 큐를 엿볼 수 있다. 클라이언트는 반드시 /user/queue/...
 * 로 구독해야 하므로(스프링이 자기 세션으로 해석) 원시 /queue 구독은 여기서 막는다.
 * 심플 브로커는 구독 목적지를 Ant 패턴으로 매칭하고 접두사도 단순 startsWith 로 보므로, 패턴 문자
 * (별표, 물음표, 중괄호)가 있으면 접두사 검사를 우회해 모든 개인 큐에 걸린다. 패턴 구독은 모두 거절한다.
 */
@Component
class StompDestinationGuard : ChannelInterceptor {

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val accessor = StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.SUBSCRIBE) return message

        val destination = accessor.destination ?: return message
        if (destination.any { it in "*?{}" }) {
            throw PermissionDeniedException("패턴 구독은 허용되지 않는다: $destination")
        }
        if (destination.startsWith("/queue")) {
            throw PermissionDeniedException("원시 /queue 구독은 허용되지 않는다: $destination")
        }

        return message
    }
}
