package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 연결 끊김 타이머 + 퇴장 예약소.
 *
 * [TurnTimer] 와 예약/토큰 패턴은 같다 - TaskScheduler 로 예약하고, 단조 증가 토큰으로 cancel()이
 * 못 막은 stale 실행을 걸러낸다. 하지만 상수를 공유하지 않는다: 턴 타이머는 "차례가 왔는데
 * 응답이 없다"(폴드한 사람에게는 차례가 다시 오지 않아 이 경로로는 못 잡는다)를 재고, 이
 * 타이머는 "자리만 차지하고 사라졌다"를 잰다. 값이 지금 우연히 같을 뿐, 만료 동작(폴드 vs 퇴장)도
 * 달라서 나중에 서로 다르게 조정될 수 있다 - 그래서 TURN_TIMEOUT 을 참조하지 않고 별도 상수를 둔다.
 *
 * 퇴장 예약([pendingStandUps])도 여기서 들고 있다. 핸드 진행 중에 연결이 만료되면
 * ExpireConnectionUseCase 는 기립시키지 않고(going south 방지) 테이블 id 만 돌려주고, 이후
 * [HandBroadcastRequested] 로 그 테이블의 핸드가 끝난 걸 확인하면 예약해둔 사용자를
 * StandUpUseCase 로 실제 기립시킨다.
 *
 * ponytail: 연결 타이머 예약과 퇴장 예약 모두 인메모리([ConcurrentHashMap])다 - 단일 인스턴스
 * 전제이고 재시작하면 전부 사라진다(DISCONNECTED 로 남았던 좌석 표시나 밀린 퇴장 예약이 없어진다).
 * 5단계(재시작 복구)가 이 자리를 다시 본다.
 */
@Component
class ConnectionTimer(
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
    private val updateSeatPresenceUseCase: UpdateSeatPresenceUseCase,
    private val expireConnectionUseCase: ExpireConnectionUseCase,
    private val standUpUseCase: StandUpUseCase,
) {
    private class ScheduledExpiry(val future: ScheduledFuture<*>, val token: Long)

    // userId 기준 예약이다 - 연결은 테이블이 아니라 사람에게 붙는다.
    private val scheduled = ConcurrentHashMap<Long, ScheduledExpiry>()
    private val tokens = ConcurrentHashMap<Long, AtomicLong>()

    // 핸드가 끝날 때까지 미뤄둔 퇴장. tableId -> 대기 중인 userId 목록.
    private val pendingStandUps = ConcurrentHashMap<TableId, MutableSet<Long>>()

    @EventListener
    fun onSessionDisconnect(event: SessionDisconnectEvent) {
        val userId = event.user?.name?.toLongOrNull() ?: return

        updateSeatPresenceUseCase.update(UpdateSeatPresenceCommand(userId, SeatPresence.DISCONNECTED.name))

        scheduled.remove(userId)?.future?.cancel(false)
        val token = tokens.computeIfAbsent(userId) { AtomicLong() }.incrementAndGet()
        try {
            val future = taskScheduler.schedule(
                { onExpire(userId, token) },
                Instant.now(clock).plus(CONNECTION_TIMEOUT),
            )
            scheduled[userId] = ScheduledExpiry(future, token)
        } catch (e: TaskRejectedException) {
            // 컨텍스트 종료 중에는 퇴장 타이머를 걸 이유가 없고, 이를 ERROR로 두면 진짜 ERROR가 묻힌다
            log.debug("컨텍스트 종료 중이라 연결 만료 예약을 걸지 못했습니다: userId={}", userId)
        }
    }

    @EventListener
    fun onSessionConnected(event: SessionConnectedEvent) {
        val userId = event.user?.name?.toLongOrNull() ?: return

        scheduled.remove(userId)?.future?.cancel(false)
        // cancel() 은 최선 노력이라, 이미 시작된 실행이 있다면 토큰을 바꿔 무효화한다.
        tokens.computeIfAbsent(userId) { AtomicLong() }.incrementAndGet()
        // 만료가 먼저 일어나 이미 퇴장 예약이 걸려 있었을 수도 있다(연결 타이머 예약과 퇴장 예약은
        // 서로 다른 생명주기라 token 만으로는 못 막는다) - 재접속했으니 그 예약도 지운다.
        pendingStandUps.values.forEach { it.remove(userId) }

        updateSeatPresenceUseCase.update(UpdateSeatPresenceCommand(userId, SeatPresence.SEATED.name))
    }

    private fun onExpire(userId: Long, token: Long) {
        if (tokens[userId]?.get() != token) return
        val reservedTableId = expireConnectionUseCase.expire(ExpireConnectionCommand(userId))
        if (reservedTableId != null) {
            pendingStandUps.computeIfAbsent(reservedTableId) { ConcurrentHashMap.newKeySet() }.add(userId)
        }
    }

    @TransactionalEventListener
    fun onHandBroadcastRequested(event: HandBroadcastRequested) {
        if (event.hand != null && !event.hand.isFinished) return
        val userIds = pendingStandUps.remove(event.tableId) ?: return
        for (userId in userIds) {
            standUpUseCase.standUp(StandUpCommand(userId))
            log.info("핸드 종료로 예약된 퇴장을 실행했습니다: userId={}, tableId={}", userId, event.tableId.value)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConnectionTimer::class.java)

        // TurnTimer.TURN_TIMEOUT 과 값은 같지만 상수를 공유하지 않는다 - 클래스 KDoc 참고.
        private val CONNECTION_TIMEOUT: Duration = Duration.ofMinutes(3)
    }
}
