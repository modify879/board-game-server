package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionUseCase
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceUseCase
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.callForUser
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 연결 끊김 타이머.
 *
 * 연결이 끊기는 것 자체는 표시(DISCONNECTED)만 바꾼다. 핸드 중에는 턴 타임아웃([TurnTimer])이 처리한다.
 * 핸드가 없을 때 `connection-timeout`(기본 3분) 동안 끊겨 있으면 퇴장시킨다. 만료 시점에 핸드가 진행 중이면
 * 퇴장시키지 않고 감시를 다시 건다(going south 방지, 핸드가 끝난 뒤의 만료가 퇴장시킨다).
 *
 * [TurnTimer] 와 예약/토큰 패턴은 같다 - TaskScheduler 로 예약하고, 단조 증가 토큰으로 cancel()이
 * 못 막은 stale 실행을 걸러낸다. 상수는 공유하지 않는다: 턴 타이머는 "차례가 왔는데 응답이 없다"(30초)를,
 * 이 타이머는 "자리만 차지하고 사라졌다"(3분)를 재고, 만료 동작(폴드 vs 퇴장)도 다르다.
 *
 * 재시작하면 예약이 사라지지만, 부팅 시 앉아 있는 사용자 전원에게 감시를 다시 건다.
 * 재시작 후 접속하지 않은 사람에게는 [SessionDisconnectEvent] 가 영영 오지 않으므로, 그 사람을
 * 치우는 경로가 이 부팅 스윕뿐이다. Tomcat 은 Ready 전에 접속을 받으므로, 그 사이 먼저 재접속한
 * 사용자를 다시 감시하면 연결된 채 기립되어 이미 연결된 사용자는 뺀다.
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
 *
 * 연결이 끊기면 대기열 멤버십도 함께 지운다([onSessionDisconnect]) — 좌석 배정 전이라
 * suspended(복구 유예) 대상이 될 일이 없으므로 무조건 지워도 안전하다. 단, 같은 사용자의 다른
 * 세션(다른 탭·기기)이 살아 있으면 지우지 않는다 — [UserConnections.hasOtherSession] 으로 확인한다.
 */
@Component
class ConnectionTimer(
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
    private val updateSeatPresenceUseCase: UpdateSeatPresenceUseCase,
    private val expireConnectionUseCase: ExpireConnectionUseCase,
    private val tables: HoldemTableRepository,
    private val joinQueue: JoinQueue,
    private val joinQueueNotifier: JoinQueueNotifier,
    private val userConnections: UserConnections,
    private val tableExecutor: TableExecutor,
    // turn-timeout 과 설정을 공유하지 않는다 — 클래스 KDoc 참고.
    @Value("\${app.holdem.connection-timeout}") private val connectionTimeout: Duration,
) {
    private class ScheduledExpiry(val future: ScheduledFuture<*>, val token: Long)

    // userId 기준 예약이다 - 연결은 테이블이 아니라 사람에게 붙는다.
    private val scheduled = ConcurrentHashMap<UserId, ScheduledExpiry>()
    private val tokens = ConcurrentHashMap<UserId, AtomicLong>()

    private val suspended = ConcurrentHashMap.newKeySet<UserId>()

    /** 사용자가 앉은 테이블의 실행기에서 [task] 를 돌린다. 앉아 있지 않으면 presence 갱신·만료 모두 할 일이 없는
     *  no-op 이라 돌리지 않고 null 을 돌려준다 — 실행기 밖에서 쓰기를 하지 않는다. */
    private fun <T> dispatchForUser(userId: UserId, task: () -> T): T? =
        tableExecutor.callForUser<T?>(tables, userId, onNotSeated = { null }, task = task)

    /** HandRecovery.onApplicationReady(@Order(0)) 다음으로 실행되어야 한다 - 클래스 KDoc 참고. */
    @EventListener(ApplicationReadyEvent::class)
    @Order(1)
    fun onApplicationReady() {
        val toWatch = tables.findAllSeatedUserIds().filterNot { it in suspended || userConnections.isConnected(it) }
        toWatch.forEach { beginWatch(it) }
        log.info("부팅 시 착석 중이던 사용자 {}명에게 연결 감시를 다시 걸었습니다.", toWatch.size)
    }

    @EventListener
    fun onSessionDisconnect(event: SessionDisconnectEvent) {
        val userId = event.user?.name?.toLongOrNull()?.let(::UserId) ?: return

        val hasOtherSession = userConnections.hasOtherSession(userId, event.sessionId)
        if (!hasOtherSession) {
            val queuedTableId = joinQueue.tableOf(userId)
            if (queuedTableId != null) {
                tableExecutor.call(queuedTableId) {
                    val removed = joinQueue.removeByUserId(userId)
                    if (removed != null) joinQueueNotifier.notifyPositions(removed, joinQueue.entriesOf(removed))
                }
            }
        }

        // 다른 탭·기기가 살아 있으면 연결이 끊긴 게 아니다 - DISCONNECTED 표시도 3분 퇴장 타이머도 걸지 않는다.
        if (hasOtherSession) return
        // 복구 유예 중에는 이 타이머가 돌지 않는다 - 서버 다운타임은 플레이어 책임이 아니다.
        if (userId in suspended) return
        beginWatch(userId)
    }

    @EventListener
    fun onSessionConnected(event: SessionConnectedEvent) {
        val userId = event.user?.name?.toLongOrNull()?.let(::UserId) ?: return

        cancelWatch(userId)

        dispatchForUser(userId) { updateSeatPresenceUseCase.update(UpdateSeatPresenceCommand(userId.value, SeatPresence.SEATED.name)) }
    }

    /**
     * 연결 감시를 (다시) 건다 - DISCONNECTED 로 표시하고 `connection-timeout` 뒤로 만료를 예약한다.
     * 일반적인 연결 끊김, 부팅 시딩, 복구 유예가 끝났는데 안 돌아온 사용자 셋 다 이 경로를 탄다.
     */
    fun beginWatch(userId: UserId) {
        suspended.remove(userId)
        schedule(userId)
        // 타이머를 먼저 건다 — DB 장애로 presence 갱신이 던져도 만료 타이머는 이미 걸려 있어야 사용자가 치워진다.
        dispatchForUser(userId) { updateSeatPresenceUseCase.update(UpdateSeatPresenceCommand(userId.value, SeatPresence.DISCONNECTED.name)) }
    }

    private fun schedule(userId: UserId) {
        scheduled.remove(userId)?.future?.cancel(false)
        val token = tokens.computeIfAbsent(userId) { AtomicLong() }.incrementAndGet()
        try {
            val future = taskScheduler.schedule(
                { onExpire(userId, token) },
                Instant.now(clock).plus(connectionTimeout),
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
    fun cancelWatch(userId: UserId) {
        suspended.remove(userId)
        scheduled.remove(userId)?.future?.cancel(false)
        // cancel() 은 최선 노력이라, 이미 시작된 실행이 있다면 토큰을 바꿔 무효화한다.
        tokens.computeIfAbsent(userId) { AtomicLong() }.incrementAndGet()
    }

    /** `HandRecovery` 가 테이블 복구를 시작하며 그 참가자들의 연결 감시를 유예시킨다. */
    fun suspendWatch(userIds: Collection<UserId>) {
        suspended.addAll(userIds)
    }

    private fun onExpire(userId: UserId, token: Long) {
        if (tokens[userId]?.get() != token || userConnections.isConnected(userId)) return
        val handInProgressTableId = try {
            dispatchForUser(userId) {
                // 스케줄러 스레드의 토큰 검사는 실행기 큐를 기다리는 사이 낡을 수 있다 - 안에서 다시 본다.
                if (tokens[userId]?.get() != token || userConnections.isConnected(userId)) null else expireConnectionUseCase.expire(ExpireConnectionCommand(userId.value))
            }
        } catch (e: Exception) {
            log.error("연결 만료 처리에 실패했습니다: userId={}", userId, e)
            rearmWatch(userId, token)
            return
        }
        // 핸드 진행 중이라 퇴장하지 않았다 - 감시를 다시 건다. 만료 트랜잭션이 도는 사이 재접속했다면
        // 토큰이 바뀌었으니 다시 걸지 않는다.
        if (handInProgressTableId != null && tokens[userId]?.get() == token && !userConnections.isConnected(userId)) schedule(userId)
    }

    /** 실패한 만료를 3분 감시로 되돌린다. 그 사이 재접속했거나 감시가 바뀌었으면(토큰·연결 확인) 건드리지 않는다. */
    private fun rearmWatch(userId: UserId, token: Long?) {
        if (tokens[userId]?.get() != token || userConnections.isConnected(userId)) return
        try {
            beginWatch(userId)
        } catch (e: Exception) {
            log.error("연결 감시를 다시 걸지 못했습니다: userId={}", userId, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConnectionTimer::class.java)
    }
}
