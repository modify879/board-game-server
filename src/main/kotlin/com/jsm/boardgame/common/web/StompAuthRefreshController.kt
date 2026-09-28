package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.CommonErrorCode
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Controller
import java.security.Principal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * CONNECT 이후에도 소켓을 새로 열지 않고 액세스 토큰을 갱신한다. StompSessionRegistry 가 세션마다
 * 만료 시각에 닫는 타이머를 걸어두므로(CONNECT 시점 등록), 클라이언트는 REST 로 리프레시해 받은
 * 새 토큰을 재연결 없이 이 경로로 실어 그 타이머를 새 만료 시각으로 옮긴다.
 *
 * CONNECT 와 같은 JwtDecoder 빈을 그대로 쓴다 — 서명·만료·블랙리스트 검증이 CONNECT 와 갈라지지
 * 않게 한다(user/infrastructure/security/config/JwtDecoderConfig 참고).
 *
 * 새 토큰의 subject 가 이 세션의 인증 주체(CONNECT 때 세팅된 principal)와 다르면 거부한다 —
 * 안 그러면 이 채널로 세션을 남의 토큰으로 바꿔치기할 수 있다.
 *
 * 호출한 세션에만 응답한다(@SendToUser broadcast=false) — 같은 사용자의 다른 탭까지 갱신
 * 결과를 받을 이유가 없다. convertAndSendToUser 가 대상 세션이 없으면 조용히 버리는 문제(규칙 6)는
 * 여기서는 해당하지 않는다 — 지금 이 프레임을 보낸 바로 그 세션에 답하는 것이라 세션이 없을 수 없다.
 */
@Controller
class StompAuthRefreshController(
    private val jwtDecoder: JwtDecoder,
    private val stompSessionRegistry: StompSessionRegistry,
    private val clock: Clock,
) {

    @MessageMapping("/auth/refresh")
    @SendToUser("/queue/auth", broadcast = false)
    fun refresh(
        payload: RefreshTokenPayload,
        principal: Principal,
        @Header("simpSessionId") sessionId: String,
    ): RefreshTokenReply {
        val jwt = try {
            jwtDecoder.decode(payload.accessToken)
        } catch (ex: JwtException) {
            log.warn("stomp in-band refresh rejected: token validation failed, reason={}", ex.message)
            return RefreshTokenReply.error(CommonErrorCode.AUTHENTICATION_REQUIRED.code)
        }

        if (jwt.subject != principal.name) {
            log.warn("stomp in-band refresh rejected: token subject does not match session principal")
            return RefreshTokenReply.error(CommonErrorCode.ACCESS_DENIED.code)
        }

        val accessTokenId = jwt.id
        val expiresAt = jwt.expiresAt
        if (accessTokenId == null || expiresAt == null) {
            log.warn("stomp in-band refresh rejected: token missing jti or exp")
            return RefreshTokenReply.error(CommonErrorCode.AUTHENTICATION_REQUIRED.code)
        }

        stompSessionRegistry.replaceToken(sessionId, accessTokenId, expiresAt)
        log.info("stomp in-band token refresh succeeded, sessionId={}", sessionId)
        val expiresInMs = Duration.between(Instant.now(clock), expiresAt).toMillis().coerceAtLeast(0)
        return RefreshTokenReply.ok(expiresAt, expiresInMs)
    }

    companion object {
        private val log = LoggerFactory.getLogger(StompAuthRefreshController::class.java)
    }
}

data class RefreshTokenPayload(val accessToken: String)

data class RefreshTokenReply(
    val result: String,
    val expiresAt: Instant? = null,
    /** [expiresAt] 까지 남은 ms(벽시계 기준 계산, 클라이언트는 받은 순간부터 자기 단조 시계로 센다). */
    val expiresInMs: Long? = null,
    val errorCode: String? = null,
) {
    companion object {
        fun ok(expiresAt: Instant, expiresInMs: Long) =
            RefreshTokenReply(result = "OK", expiresAt = expiresAt, expiresInMs = expiresInMs)
        fun error(errorCode: String) = RefreshTokenReply(result = "ERROR", errorCode = errorCode)
    }
}
