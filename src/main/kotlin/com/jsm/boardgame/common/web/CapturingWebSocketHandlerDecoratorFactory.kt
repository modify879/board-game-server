package com.jsm.boardgame.common.web

import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.WebSocketHandlerDecorator
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory

/**
 * 소켓이 열리고 닫힐 때 WebSocketSession 객체 자체를 [StompSessionRegistry] 에 등록/해제한다.
 * [StompAuthenticationInterceptor] 는 STOMP 프레임(메시지)만 보고 소켓을 직접 쥐지 않는다 -
 * [StompSessionRevalidator] 가 무효한 토큰을 찾아 소켓을 강제로 닫으려면 이 WebSocketSession 이
 * 필요하다.
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

            override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
                stompSessionRegistry.remove(session.id)
                super.afterConnectionClosed(session, closeStatus)
            }
        }
}
