package com.jsm.boardgame.common.web

import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.WebSocketHandlerDecorator
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory

/**
 * 소켓이 열릴 때 WebSocketSession 객체 자체를 [StompSessionRegistry] 에 등록한다.
 * [StompAuthenticationInterceptor] 는 STOMP 프레임(메시지)만 보고 소켓을 직접 쥐지 않는다 -
 * [StompSessionRegistry] 가 예약된 만료 시각에, 혹은 명시적 폐기 요청에 소켓을 직접 닫으려면
 * 이 WebSocketSession 이 필요하다. 정리(해제)는 여기서 하지 않는다 - 세션 종료 시의 등록 해제는
 * [StompSessionRegistry.onSessionDisconnect] 가, 즉시/유예 폐기 시의 해제는 그 내부 닫기 함수가 맡는다.
 */
@Component
class CapturingWebSocketHandlerDecoratorFactory(
    private val stompSessionRegistry: StompSessionRegistry,
) : WebSocketHandlerDecoratorFactory {

    override fun decorate(handler: WebSocketHandler): WebSocketHandler =
        object : WebSocketHandlerDecorator(handler) {
            override fun afterConnectionEstablished(session: WebSocketSession) {
                stompSessionRegistry.registerSession(session.id, session)
                super.afterConnectionEstablished(session)
            }
        }
}
