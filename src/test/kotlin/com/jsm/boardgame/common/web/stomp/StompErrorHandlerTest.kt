package com.jsm.boardgame.common.web.stomp

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.common.error.CommonErrorCode
import com.jsm.boardgame.common.error.PermissionDeniedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException

class StompErrorHandlerTest {

    private val handler = StompErrorHandler()

    private fun errorCodeFor(cause: Throwable): String? {
        val client = StompHeaderAccessor.create(StompCommand.CONNECT)
        val message = MessageBuilder.createMessage(ByteArray(0), client.messageHeaders)
        val error = handler.handleClientMessageProcessingError(message, cause)!!
        return StompHeaderAccessor.wrap(error).getFirstNativeHeader(StompErrorHandler.ERROR_CODE_HEADER)
    }

    private fun wrapped(cause: Throwable) = MessageDeliveryException(MessageBuilder.createMessage(ByteArray(0), StompHeaderAccessor.create(StompCommand.CONNECT).messageHeaders), cause)

    @Test
    fun `채널이 감싼 BusinessException 은 자기 errorCode 를 싣는다`() {
        assertThat(errorCodeFor(wrapped(AuthenticationRequiredException("x"))))
            .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED.code)
        assertThat(errorCodeFor(wrapped(PermissionDeniedException("x"))))
            .isEqualTo(CommonErrorCode.ACCESS_DENIED.code)
    }

    @Test
    fun `시큐리티 AuthenticationException 은 AUTHENTICATION_REQUIRED 다`() {
        assertThat(errorCodeFor(wrapped(BadCredentialsException("x"))))
            .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED.code)
    }

    @Test
    fun `시큐리티 AccessDeniedException 은 ACCESS_DENIED 다`() {
        assertThat(errorCodeFor(wrapped(AccessDeniedException("x"))))
            .isEqualTo(CommonErrorCode.ACCESS_DENIED.code)
    }

    @Test
    fun `그 밖의 원인은 INTERNAL_ERROR 다`() {
        assertThat(errorCodeFor(wrapped(IllegalStateException("boom"))))
            .isEqualTo(CommonErrorCode.INTERNAL_ERROR.code)
    }
}
