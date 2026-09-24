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
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
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
 * 재시작하면 예약이 사라지지만, 부팅 시 앉아 있는 사용자 전원에게 감시를 다시 건다.
 * 재시작 후 접속하지 않은 사람에게는 [SessionDisconnectEvent] 가 영영 오지 않으므로, 그 사람을
 * 치우는 경로가 이 부팅 스윕뿐이다.
 *
 * 복구 유예 중인 테이블([HandRecovery] 가 진행 중 핸드를 복구하는 3분 동안)의 참가자는 이 부팅
 * 시딩에서 빠져야 한다 - 서버가 죽어있던 시간은 플레이어 책임이 아니다. pull 방식
 * (`HandRecovery.isAwaitingRecovery(userId)` 조회)은 복구가 끝났을 때 안 돌아온 사람에게 감시를
 * 새로 걸어야 하는 요구와 합치면(`HandRecovery` 가 이 컴포넌트를 다시 불러야 한다) 순환 빈
 * 의존이 된다. 그래서 [suspendWatch] 로 `HandRecovery` 가 유예 대상을 이 컴포넌트에 미리
 * 알려주고(push), [beginWatch]/[cancelWatch] 로 복구가 끝난 결과를 반영하게 한다. 새 포트
 * 인터페이스는 두지 않는다 - 둘 다 같은 infrastructure 계층의 구체 타입이라 직접 의존으로
 * 충분하다.
 *
 * [onApplicationReady] 와 `HandRecovery.onApplicationReady` 는 같은 `ApplicationReadyEvent` 를
 * 듣는다. `HandRecovery` 쪽이 먼저 실행되어 복구 대상 테이블의 참가자를 [suspendWatch] 로 걸어둬야
 * 이 컴포넌트의 부팅 시딩이 그들을 뺄 수 있어, `@Order` 로 순서를 강제한다(낮은 값이 먼저 실행).
 */
@Component
class ConnectionTimer(
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
    private val updateSeatPresenceUseCase: UpdateSeatPresenceUseCase,
    private val expireConnectionUseCase: ExpireConnectionUseCase,
    private val standUpUseCase: StandUpUseCase,
    private val tables: HoldemTableRepository,
) {
    private class ScheduledExpiry(val future: ScheduledFuture<*>, val token: Long)

    // userId 기준 예약이다 - 연결은 테이블이 아니라 사람에게 붙는다.
    private val scheduled = ConcurrentHashMap<Long, ScheduledExpiry>()
    private val tokens = ConcurrentHashMap<Long, AtomicLong>()

    private val pendingStandUps = ConcurrentHashMap<TableId, MutableSet<Long>>()

    private val suspended = ConcurrentHashMap.newKeySet<Long>()

    /** HandRecovery.onApplicationReady(@Order(0)) 다음으로 실행되어야 한다 - 클래스 KDoc 참고. */
    @EventListener(ApplicationReadyEvent::class)
    @Order(1)
    fun onApplicationReady() {
        val toWatch = tables.findAllSeatedUserIds().filterNot { it in suspended }
        toWatch.forEach { beginWatch(it) }
        log.info("부팅 시 착석 중이던 사용자 {}명에게 연결 감시를 다시 걸었습니다.", toWatch.size)
    }

    @EventListener
    fun onSessionDisconnect(event: SessionDisconnectEvent) {
        val userId = event.user?.name?.toLongOrNull() ?: return
        // 복구 유예 중에는 이 타이머가 돌지 않는다 - 서버 다운타임은 플레이어 책임이 아니다.
        if (userId in suspended) return
        beginWatch(userId)
    }

    @EventListener
    fun onSessionConnected(event: SessionConnectedEvent) {
        val userId = event.user?.name?.toLongOrNull() ?: return

        cancelWatch(userId)
        // 만료가 먼저 일어나 이미 퇴장 예약이 걸려 있었을 수도 있다(연결 타이머 예약과 퇴장 예약은
        // 서로 다른 생명주기라 token 만으로는 못 막는다) - 재접속했으니 그 예약도 지운다.
        pendingStandUps.values.forEach { it.remove(userId) }

        updateSeatPresenceUseCase.update(UpdateSeatPresenceCommand(userId, SeatPresence.SEATED.name))
    }

    /**
     * 연결 감시를 (다시) 건다 - DISCONNECTED 로 표시하고 [CONNECTION_TIMEOUT] 뒤로 만료를 예약한다.
     * 일반적인 연결 끊김, 부팅 시딩, 복구 유예가 끝났는데 안 돌아온 사용자 셋 다 이 경로를 탄다.
     */
    fun beginWatch(userId: Long) {
        suspended.remove(userId)
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

    /**
     * 예약된 만료를 지우고 유예 표시도 지운다. 재접속, 그리고 복구가 끝났을 때 이미 돌아와 있던
     * 사용자에게 쓴다 - 유예 중엔 [onSessionDisconnect] 가 애초에 감시를 걸지 않으므로 지울 예약이
     * 없는 게 정상이지만, 방어적으로 같이 처리한다.
     */
    fun cancelWatch(userId: Long) {
        suspended.remove(userId)
        scheduled.remove(userId)?.future?.cancel(false)
        // cancel() 은 최선 노력이라, 이미 시작된 실행이 있다면 토큰을 바꿔 무효화한다.
        tokens.computeIfAbsent(userId) { AtomicLong() }.incrementAndGet()
    }

    /** `HandRecovery` 가 테이블 복구를 시작하며 그 참가자들의 연결 감시를 유예시킨다. */
    fun suspendWatch(userIds: Collection<Long>) {
        suspended.addAll(userIds)
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
