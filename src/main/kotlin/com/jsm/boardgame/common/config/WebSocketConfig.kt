package com.jsm.boardgame.common.config

import com.jsm.boardgame.common.web.CapturingWebSocketHandlerDecoratorFactory
import com.jsm.boardgame.common.web.StompAuthenticationInterceptor
import com.jsm.boardgame.common.web.StompDestinationGuard
import com.jsm.boardgame.common.web.StompErrorHandler
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.converter.MessageConverter
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

/**
 * CONNECT 인증은 StompAuthenticationInterceptor, 오류 응답 계약은 StompErrorHandler 가 맡는다.
 * CapturingWebSocketHandlerDecoratorFactory 는 살아있는 WebSocketSession 객체 자체를
 * StompSessionRegistry 에 등록해, StompSessionRegistry 가 나중에 그 세션을 직접 close() 할 수
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

    // 하트비트 전용. 빈으로 노출하면 TaskScheduler 를 타입으로 주입받는 holdem 타이머들이 모호해지고,
    // 게임 타이머와 스레드를 공유하면 하트비트가 밀린다 — 그래서 빈이 아닌 이 설정 소유의 인스턴스다.
    private val heartbeatScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("ws-heartbeat-")
        initialize()
    }

    @PreDestroy
    fun shutdownHeartbeatScheduler() = heartbeatScheduler.shutdown()

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
        registry.setErrorHandler(stompErrorHandler)
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // 서버→클라이언트 하트비트만 10초 간격으로 보낸다. 클라이언트 하트비트는 기대하지 않는다(0) —
        // 숨겨진 브라우저 탭에서는 Godot Web 메인 루프가 멈춰 하트비트를 못 보내고, 기대하면 플레이어가
        // 끊겨 참가 대기열에서 빠진다. TaskScheduler 가 없으면 스프링은 하트비트 설정을 무시한다.
        registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(longArrayOf(10_000, 0))
            .setTaskScheduler(heartbeatScheduler)
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
        // clientOutboundChannel 은 스레드풀이라 기본값으로는 같은 세션으로 보낸 메시지도 순서가
        // 섞일 수 있다(Spring 문서 "Order of Messages") — 세션별 발행 순서를 보존한다.
        registry.setPreservePublishOrder(true)
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
