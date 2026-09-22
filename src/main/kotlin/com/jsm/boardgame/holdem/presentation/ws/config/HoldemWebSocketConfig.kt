package com.jsm.boardgame.holdem.presentation.ws.config

import com.jsm.boardgame.holdem.presentation.ws.HoldemSubscriptionInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * 홀덤만의 구독 인가를 인바운드 채널에 더한다.
 * common.config.WebSocketConfig 는 holdem 패키지를 몰라도 된다 — 스프링이
 * WebSocketMessageBrokerConfigurer 빈을 전부 찾아 합쳐 호출해주기 때문이다
 * (DelegatingWebSocketMessageBrokerConfiguration). 새 게임이 생기면 그 게임도 자기
 * presentation/ws/config 에 같은 방식으로 하나 더 두면 된다.
 */
@Configuration
class HoldemWebSocketConfig(
    private val holdemSubscriptionInterceptor: HoldemSubscriptionInterceptor,
) : WebSocketMessageBrokerConfigurer {

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(holdemSubscriptionInterceptor)
    }
}
