package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.common.error.CommonErrorCode
import org.springframework.messaging.Message
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
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
 * cause 체인을 타고 내려가며 BusinessException 을 찾는다. 못 찾으면(우리 인터셉터가 아닌 다른
 * 이유로 실패한 경우) 기본값으로 AUTHENTICATION_REQUIRED 를 쓴다.
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

    private fun errorCodeOf(cause: Throwable?): String =
        generateSequence(cause) { it.cause }
            .filterIsInstance<BusinessException>()
            .firstOrNull()
            ?.errorCode
            ?.code
            ?: CommonErrorCode.AUTHENTICATION_REQUIRED.code

    companion object {
        const val ERROR_CODE_HEADER = "errorCode"
    }
}
