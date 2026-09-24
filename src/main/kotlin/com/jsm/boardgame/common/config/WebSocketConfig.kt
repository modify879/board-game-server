package com.jsm.boardgame.common.config

import com.jsm.boardgame.common.web.CapturingWebSocketHandlerDecoratorFactory
import com.jsm.boardgame.common.web.StompAuthenticationInterceptor
import com.jsm.boardgame.common.web.StompDestinationGuard
import com.jsm.boardgame.common.web.StompErrorHandler
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.converter.MessageConverter
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

/**
 * STOMP 엔드포인트(/ws)와 심플 브로커(/topic, /queue)를 연다. 애플리케이션 프리픽스는 /app,
 * 사용자 프리픽스는 /user 다.
 *
 * CONNECT 인증은 StompAuthenticationInterceptor, 오류 응답 계약은 StompErrorHandler 가 맡는다.
 * CapturingWebSocketHandlerDecoratorFactory 는 살아있는 WebSocketSession 객체 자체를
 * StompSessionRegistry 에 등록해, StompSessionRevalidator 가 나중에 그 세션을 직접 close() 할 수
 * 있게 한다 - STOMP 인터셉터가 보는 건 프레임이지 소켓이 아니다.
 * 이 파일은 common 이므로 어떤 컨텍스트 패키지도 import 하지 않는다 — 게임별 구독 인가
 * (예: holdem 의 HoldemSubscriptionInterceptor)는 각 컨텍스트가 자기 WebSocketMessageBrokerConfigurer
 * 를 하나 더 등록해 더한다. 스프링이 여러 configurer 빈을 자동으로 찾아 합쳐 호출해주기 때문이다.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val stompAuthenticationInterceptor: StompAuthenticationInterceptor,
    private val stompDestinationGuard: StompDestinationGuard,
    private val stompErrorHandler: StompErrorHandler,
    private val capturingWebSocketHandlerDecoratorFactory: CapturingWebSocketHandlerDecoratorFactory,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
        registry.setErrorHandler(stompErrorHandler)
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(stompAuthenticationInterceptor, stompDestinationGuard)
    }

    override fun configureWebSocketTransport(registry: WebSocketTransportRegistration) {
        registry.addDecoratorFactory(capturingWebSocketHandlerDecoratorFactory)
    }

    override fun configureMessageConverters(messageConverters: MutableList<MessageConverter>): Boolean {
        // Spring Framework 7 은 MappingJackson2MessageConverter 를 JacksonJsonMessageConverter 로
        // 대체했다. 클래스패스에 둘 다 있어 기본값에 맡기면 옛 컨버터가 골라질 수 있어 명시한다.
        messageConverters.add(JacksonJsonMessageConverter())
        return false
    }
}
