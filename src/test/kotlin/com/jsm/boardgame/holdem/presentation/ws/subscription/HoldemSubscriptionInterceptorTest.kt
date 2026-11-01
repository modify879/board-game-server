package com.jsm.boardgame.holdem.presentation.ws.subscription

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.common.error.CommonErrorCode
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TablePolicyView
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.presentation.ws.HoldemDestinations
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import java.security.Principal

private class SubInterceptorFakeQuery(private val seat: SeatLocation? = null) : HoldemTableQueryRepository {
    override fun findSeatOf(userId: Long): SeatLocation? = seat
    override fun findPolicyOf(tableId: Long): TablePolicyView? = error("unused")
    override fun existsHandInProgress(tableId: Long): Boolean = error("unused")
    override fun findTableIdsWithHandInProgress(tableIds: Collection<Long>): Set<Long> = error("unused")
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> = error("unused")
    override fun findEmptyTableIds(): List<Long> = error("unused")
}

class HoldemSubscriptionInterceptorTest {

    private val interceptor = HoldemSubscriptionInterceptor(SubInterceptorFakeQuery())
    private val channel = MessageChannel { _, _ -> true }

    private fun subscribe(destination: String, principalName: String?) = StompHeaderAccessor.create(StompCommand.SUBSCRIBE).let {
        it.destination = destination
        it.setSubscriptionId("sub-0")
        principalName?.let { name -> it.user = Principal { name } }
        MessageBuilder.createMessage(ByteArray(0), it.messageHeaders)
    }

    private fun assertAuthRequired(destination: String, principalName: String?) {
        assertThatThrownBy { interceptor.preSend(subscribe(destination, principalName), channel) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED)
            }
    }

    @Test
    fun `인증 주체 없이 대기열 채널을 구독하면 AUTHENTICATION_REQUIRED 로 거부한다`() {
        assertAuthRequired(HoldemDestinations.joinQueueDestination(), null)
    }

    @Test
    fun `인증 주체 없이 테이블 공개 토픽을 구독하면 AUTHENTICATION_REQUIRED 로 거부한다`() {
        assertAuthRequired(HoldemDestinations.publicTopicOf(1), null)
    }

    @Test
    fun `인증 주체 없이 테이블 개인 큐를 구독하면 AUTHENTICATION_REQUIRED 로 거부한다`() {
        assertAuthRequired(HoldemDestinations.privateQueueOf(1), null)
    }

    @Test
    fun `principal 이름이 숫자가 아니면 AUTHENTICATION_REQUIRED 로 거부한다`() {
        assertAuthRequired(HoldemDestinations.publicTopicOf(1), "abc")
        assertAuthRequired(HoldemDestinations.joinQueueDestination(), "abc")
    }

    private fun privateSubscribeResult(seat: SeatLocation?) =
        HoldemSubscriptionInterceptor(SubInterceptorFakeQuery(seat))
            .preSend(subscribe(HoldemDestinations.privateQueueOf(1), "7"), channel)

    @Test
    fun `해당 테이블에 앉은 사용자는 개인 큐를 구독할 수 있다`() {
        assertThat(privateSubscribeResult(SeatLocation(1, 0, 1000))).isNotNull()
    }

    @Test
    fun `다른 테이블에 앉은 사용자의 개인 큐 구독은 조용히 버린다`() {
        assertThat(privateSubscribeResult(SeatLocation(2, 0, 1000))).isNull()
    }

    @Test
    fun `앉지 않은 사용자의 개인 큐 구독은 조용히 버린다`() {
        assertThat(privateSubscribeResult(null)).isNull()
    }
}
