package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.convert.converter.Converter
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component

/**
 * STOMP CONNECT 프레임의 Authorization 헤더를 검증해 세션의 인증 주체를 세팅한다.
 * `/ws` 로 시작하는 요청은 시큐리티 필터 체인에서 permitAll 이므로(common/config/SecurityConfig), 이
 * 인터셉터가 CONNECT 시점의 유일한 인증 관문이다.
 *
 * 검증은 REST 리소스 서버가 쓰는 것과 같은 JwtDecoder 빈을 그대로 주입받아 쓴다. CONNECT 전용
 * 검증 경로를 새로 만들면 그 디코더에 물려 있는 블랙리스트 검증(로그아웃·역할변경 무효화)이 빠진다.
 *
 * CONNECT 이후로는 이 인터셉터가 다시 불리지 않는다 - STOMP 세션이 여기서 세팅한 인증 주체를
 * 이후 모든 프레임에 그대로 실어준다. 그래서 raw 토큰을 [StompSessionRegistry] 에 남겨
 * [StompSessionRevalidator] 가 30초마다 같은 JwtDecoder 로 다시 검증할 수 있게 한다.
 *
 * @Order 를 구독 인가 인터셉터(HoldemSubscriptionInterceptor 등)보다 앞세운 이유는 인가가
 * principal 을 읽어야 하기 때문이다. 실제로는 CONNECT 와 SUBSCRIBE 가 서로 다른 프레임이라
 * 이 인터셉터끼리의 실행 순서 자체는 기능에 영향을 주지 않는다 — STOMP 세션이 CONNECT 때
 * 세팅된 인증 주체를 이후 모든 프레임에 자동으로 실어주기 때문이다. 그래도 의도를 코드로
 * 남기기 위해 순서를 명시한다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
@Component
class StompAuthenticationInterceptor(
    private val jwtDecoder: JwtDecoder,
    private val jwtAuthenticationConverter: Converter<Jwt, out AbstractAuthenticationToken>,
    private val stompSessionRegistry: StompSessionRegistry,
) : ChannelInterceptor {

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        // StompHeaderAccessor.wrap(message) 는 항상 새 인스턴스를 만든다. CONNECT 프레임에는
        // StompSubProtocolHandler 가 세션에 붙일 accessor 를 만들어 setUserChangeCallback 을
        // 걸어뒀는데, wrap() 으로 새 인스턴스를 쓰면 그 콜백이 없어 setUser() 를 호출해도 세션에
        // 반영되지 않는다 — 이후 SUBSCRIBE 프레임에서 principal 이 계속 null 로 보인다.
        // 반드시 메시지에 이미 붙어 있는 accessor 를 그대로 가져와 써야 한다.
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.CONNECT) return message

        val authorizationHeader = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER)
        val token = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?: run {
                log.warn("stomp connect rejected: missing or malformed Authorization header")
                throw AuthenticationRequiredException("STOMP CONNECT 에 Bearer 토큰이 없다")
            }

        val jwt = try {
            jwtDecoder.decode(token)
        } catch (ex: JwtException) {
            log.warn("stomp connect rejected: token validation failed, reason={}", ex.message)
            throw AuthenticationRequiredException("STOMP CONNECT 토큰 검증에 실패했다")
        }

        val authentication = jwtAuthenticationConverter.convert(jwt)
            ?: throw AuthenticationRequiredException("STOMP CONNECT 토큰에서 인증 정보를 만들 수 없다")
        accessor.user = authentication
        accessor.sessionId?.let { stompSessionRegistry.registerToken(it, token) }

        return message
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private val log = LoggerFactory.getLogger(StompAuthenticationInterceptor::class.java)
    }
}
