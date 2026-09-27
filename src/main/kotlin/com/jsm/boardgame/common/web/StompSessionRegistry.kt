package com.jsm.boardgame.common.web

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * STOMP 세션마다 (액세스 토큰 jti, userId, 만료 시각에 닫도록 예약된 작업) 을 기억해둔다.
 * [StompAuthenticationInterceptor] 가 CONNECT 시점에 [register] 로 등록하고, 인밴드 토큰 갱신
 * 컨트롤러가 [replaceToken] 으로 그 예약을 새 만료 시각으로 옮긴다. user 컨텍스트가 토큰을
 * 블랙리스트에 올릴 때는 [closeAllOf]/[closeAfterGrace] 로 그 세션을 앞당겨 닫는다.
 *
 * [CapturingWebSocketHandlerDecoratorFactory] 가 소켓이 열릴 때 실제 닫을 수 있는 WebSocketSession
 * 객체를 별도로 채운다 — STOMP 인터셉터가 보는 건 프레임이지 소켓이 아니다.
 *
 * 예전에는 30초마다 모든 세션의 토큰을 다시 검증했다(StompSessionRevalidator, 삭제됨). 지금은
 * 세션마다 정확히 하나의 예약 작업만 떠 있다 — 만료 시각과 폐기 시각 중 먼저 오는 쪽에 닫힌다.
 *
 * 세션 종료는 [SessionDisconnectEvent] 하나로 정리한다 — 소켓이 정상 종료되든 STOMP DISCONNECT
 * 프레임이든 결국 이 이벤트가 뒤따르므로 여기 한 곳만 지우면 된다. 남아 있는 예약 작업은 반드시
 * 취소한다 — 안 지우면 이미 없는 세션에 대해 뒤늦게 닫기를 시도한다(해는 없지만 낭비다).
 *
 * 일반 WebSocket(SockJS 미사용)에서는 STOMP 의 simpSessionId 와 WebSocketSession.id 가 같은 문자열이다
 * — HoldemStompIntegrationTest 에서 두 맵의 키가 겹치는 것으로 확인한다. 다르다면 여기서 닫을
 * 소켓을 못 찾아 조용히 아무 일도 안 한다.
 *
 * ponytail: Entry 필드 변경은 전부 인스턴스 락(`@Synchronized`) 하나로 막는다. 세션 수가 커져
 * 경합이 보이면 세션별 락으로 쪼갠다.
 */
@Component
class StompSessionRegistry(
    private val clock: Clock,
    private val stompSessionScheduler: TaskScheduler,
) {
    private class Entry(
        var accessTokenId: String,
        val userId: Long,
        var deadline: Instant,
        var closeFuture: ScheduledFuture<*>,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sessionsBySessionId = ConcurrentHashMap<String, WebSocketSession>()

    fun registerSession(sessionId: String, session: WebSocketSession) {
        sessionsBySessionId[sessionId] = session
    }

    /** 테스트가 simpSessionId == WebSocketSession.id 가정을 확인하는 용도. */
    fun sessionIds(): Set<String> = sessionsBySessionId.keys.toSet()

    /** 테스트가 같은 가정을 등록된 세션 쪽에서도 확인하는 용도. */
    fun registeredSessionIds(): Set<String> = entries.keys.toSet()

    /** CONNECT 성공 시 호출한다. 그 세션을 [accessTokenExpiresAt] 에 닫도록 예약한다. */
    @Synchronized
    fun register(sessionId: String, accessTokenId: String, userId: Long, accessTokenExpiresAt: Instant) {
        entries[sessionId]?.closeFuture?.cancel(false)
        val future = scheduleCloseAt(sessionId, accessTokenExpiresAt)
        entries[sessionId] = Entry(accessTokenId, userId, accessTokenExpiresAt, future)
    }

    /**
     * 그 사용자로 연결된 세션을 전부 지금 닫는다. jti 가 아니라 userId 로 찾는다 — 인밴드 갱신이
     * [replaceToken] 으로 세션의 jti 를 이미 옮긴 뒤라면 옛 jti 로는 그 세션을 찾지 못해 놓친다.
     * 실제 소켓 종료는 [stompSessionScheduler] 에서 하도록 예약만 한다 — 호출자(로그인/로그아웃
     * 요청 스레드)가 소켓 I/O 로 블로킹되지 않게 한다.
     */
    @Synchronized
    fun closeAllOf(userId: Long) {
        for ((sessionId, entry) in entries) {
            if (entry.userId == userId) {
                entry.closeFuture.cancel(false)
                stompSessionScheduler.schedule({ closeSession(sessionId) }, Instant.now(clock))
            }
        }
    }

    /**
     * 그 액세스 토큰으로 연결된 세션들의 마감을 [grace] 뒤로 늦추되, 원래 마감(만료 시각)보다
     * 늦어지지는 않는다 — 리프레시 성공 직후 클라이언트가 REST 응답을 받고 곧바로 인밴드로
     * 새 토큰을 보낼 시간을 벌어주는 용도라, 원래 마감이 이미 더 이르면 그대로 둔다.
     */
    @Synchronized
    fun closeAfterGrace(accessTokenId: String, grace: Duration) {
        val candidate = Instant.now(clock).plus(grace)
        for ((sessionId, entry) in entries) {
            if (entry.accessTokenId == accessTokenId && candidate.isBefore(entry.deadline)) {
                entry.closeFuture.cancel(false)
                entry.deadline = candidate
                entry.closeFuture = scheduleCloseAt(sessionId, candidate)
            }
        }
    }

    /** 인밴드 토큰 갱신 성공 시 호출한다. 그 세션 하나의 예약을 새 토큰·만료 시각으로 옮긴다. */
    @Synchronized
    fun replaceToken(sessionId: String, newAccessTokenId: String, newAccessTokenExpiresAt: Instant) {
        val entry = entries[sessionId] ?: return
        entry.closeFuture.cancel(false)
        entry.accessTokenId = newAccessTokenId
        entry.deadline = newAccessTokenExpiresAt
        entry.closeFuture = scheduleCloseAt(sessionId, newAccessTokenExpiresAt)
    }

    private fun scheduleCloseAt(sessionId: String, at: Instant): ScheduledFuture<*> =
        stompSessionScheduler.schedule({ closeSession(sessionId) }, at)

    @Synchronized
    private fun closeSession(sessionId: String) {
        val entry = entries.remove(sessionId) ?: return
        val session = sessionsBySessionId.remove(sessionId)
        session?.close(CloseStatus.POLICY_VIOLATION.withReason(AUTHENTICATION_REQUIRED_REASON))
        log.info("stomp session closed, sessionId={}, userId={}", sessionId, entry.userId)
    }

    @Synchronized
    @EventListener
    fun onSessionDisconnect(event: SessionDisconnectEvent) {
        entries.remove(event.sessionId)?.closeFuture?.cancel(false)
        sessionsBySessionId.remove(event.sessionId)
    }

    companion object {
        private const val AUTHENTICATION_REQUIRED_REASON = "AUTHENTICATION_REQUIRED"
        private val log = LoggerFactory.getLogger(StompSessionRegistry::class.java)
    }
}
