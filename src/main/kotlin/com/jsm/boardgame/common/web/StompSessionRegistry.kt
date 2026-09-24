package com.jsm.boardgame.common.web

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * STOMP 세션 id 로 (CONNECT 때 받은 raw 토큰, 살아있는 WebSocketSession) 을 기억해둔다.
 * [StompAuthenticationInterceptor] 가 CONNECT 시점에 토큰을, [CapturingWebSocketHandlerDecoratorFactory]
 * 가 소켓이 열릴 때 세션 객체를 채운다. [StompSessionRevalidator] 가 이 레지스트리를 훑어 30초마다
 * 토큰을 재검증하고, 무효화된 세션을 여기서 찾아 닫는다.
 *
 * 세션 종료는 [SessionDisconnectEvent] 하나로 정리한다 - 소켓이 정상 종료되든 STOMP DISCONNECT
 * 프레임이든 결국 이 이벤트가 뒤따르므로 여기 한 곳만 지우면 된다.
 *
 * 일반 WebSocket(SockJS 미사용)에서는 STOMP 의 simpSessionId 와 WebSocketSession.id 가 같은 문자열이다
 * - HoldemStompIntegrationTest 에서 두 맵의 키가 겹치는 것으로 확인한다. 다르다면 sweep() 이 토큰은
 * 재검증하고도 닫을 소켓을 못 찾아 조용히 아무 일도 안 한다.
 */
@Component
class StompSessionRegistry {
    private val tokensBySessionId = ConcurrentHashMap<String, String>()
    private val sessionsBySessionId = ConcurrentHashMap<String, WebSocketSession>()

    fun registerToken(sessionId: String, token: String) {
        tokensBySessionId[sessionId] = token
    }

    fun registerSession(sessionId: String, session: WebSocketSession) {
        sessionsBySessionId[sessionId] = session
    }

    fun sessionFor(sessionId: String): WebSocketSession? = sessionsBySessionId[sessionId]

    /** [StompSessionRevalidator] 가 스윕할 대상 스냅샷. 순회 중 갱신에 영향받지 않도록 복사본을 돌려준다. */
    fun tokenSnapshot(): Map<String, String> = tokensBySessionId.toMap()

    /** 테스트가 simpSessionId == WebSocketSession.id 가정을 확인하는 용도. */
    fun sessionIds(): Set<String> = sessionsBySessionId.keys.toSet()

    fun remove(sessionId: String) {
        tokensBySessionId.remove(sessionId)
        sessionsBySessionId.remove(sessionId)
    }

    @EventListener
    fun onSessionDisconnect(event: SessionDisconnectEvent) {
        remove(event.sessionId)
    }
}
