package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.common.error.CommonErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder

class StompDestinationGuardTest {

    private val guard = StompDestinationGuard()
    private val channel = MessageChannel { _, _ -> true }

    private fun subscribe(destination: String): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE)
        accessor.destination = destination
        accessor.setSubscriptionId("sub-0")
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/queue*/**", "/queue*", "/**", "/*/tables/1", "/topic/tables/{id}",
            "/queue/tables/1-userabc", "/queueX", "/queue", "/topic/tables/?",
        ],
    )
    fun `원시 큐나 패턴 목적지 구독은 ACCESS_DENIED 로 거절한다`(destination: String) {
        assertThatThrownBy { guard.preSend(subscribe(destination), channel) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(CommonErrorCode.ACCESS_DENIED)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/user/queue/tables/1", "/topic/tables/1", "/user/queue/holdem/join-queue"])
    fun `허용된 목적지 구독은 메시지를 그대로 통과시킨다`(destination: String) {
        val message = subscribe(destination)
        assertThat(guard.preSend(message, channel)).isSameAs(message)
    }
}
