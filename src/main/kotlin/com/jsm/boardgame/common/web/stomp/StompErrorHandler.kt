package com.jsm.boardgame.common.web.stomp

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.common.error.CommonErrorCode
import org.springframework.messaging.Message
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler

/**
 * STOMP ERROR 프레임에도 REST 와 같은 오류 계약을 싣는다 (규칙 8: errorCode 는 나가고
 * 상세 문구는 안 나간다).
 *
 * 계약: ERROR 프레임의 네이티브 STOMP 헤더 errorCode 하나로 통일한다. 클라이언트는 이
 * 헤더만 읽으면 된다 — message 헤더(사람이 읽는 문구)는 계약이 아니라 참고용이다.
 *
 * ChannelInterceptor 가 던진 예외는 채널이 MessageDeliveryException 으로 한 번 감싸 전달하므로,
 * cause 체인을 타고 내려가며 찾는다. 우리 인터셉터는 전부 BusinessException 을 던지고
 * (JwtException 도 인터셉터가 AuthenticationRequiredException 으로 바꾼다) 그 코드를 그대로 쓴다.
 * 그 밖의 원인은 체인에서 먼저 만나는 것으로 정한다 — Spring Security AuthenticationException 은
 * AUTHENTICATION_REQUIRED, AccessDeniedException 은 ACCESS_DENIED, 나머지는 INTERNAL_ERROR.
 */
@Component
class StompErrorHandler : StompSubProtocolErrorHandler() {

    override fun handleInternal(
        errorHeaderAccessor: StompHeaderAccessor,
        errorPayload: ByteArray,
        cause: Throwable?,
        clientHeaderAccessor: StompHeaderAccessor?,
    ): Message<ByteArray> {
        errorHeaderAccessor.setNativeHeader(ERROR_CODE_HEADER, errorCodeOf(cause))
        return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor)
    }

    private fun errorCodeOf(cause: Throwable?): String {
        val chain = generateSequence(cause) { it.cause }.toList()
        chain.filterIsInstance<BusinessException>().firstOrNull()?.let { return it.errorCode.code }
        val code = when {
            chain.any { it is AuthenticationException } -> CommonErrorCode.AUTHENTICATION_REQUIRED
            chain.any { it is AccessDeniedException } -> CommonErrorCode.ACCESS_DENIED
            else -> CommonErrorCode.INTERNAL_ERROR
        }
        return code.code
    }

    companion object {
        const val ERROR_CODE_HEADER = "errorCode"
    }
}
