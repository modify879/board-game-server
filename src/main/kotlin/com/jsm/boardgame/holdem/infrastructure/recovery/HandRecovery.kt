package com.jsm.boardgame.holdem.infrastructure.recovery

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandUseCase
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.infrastructure.timer.ConnectionTimer
import com.jsm.boardgame.holdem.infrastructure.timer.runRetrying
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.socket.messaging.SessionConnectedEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 부팅 시 진행 중 핸드를 복구한다.
 *
 * `ApplicationReadyEvent` 를 쓰는 이유 - 생성자/`@PostConstruct` 시점에는 이 컴포넌트가 의존하는
 * `TaskScheduler`·DB 커넥션 풀 등 다른 빈들의 초기화 순서가 보장되지 않고, 서버가 아직 STOMP
 * 연결을 받을 준비도 안 됐다. `ApplicationReadyEvent` 는 컨텍스트가 완전히 뜨고 트래픽을 받을
 * 준비가 된 뒤에 발행되어, 이 시점부터 걸어두는 3분 유예와 그 이후 들어오는 재접속을 안전하게
 * 맞춰볼 수 있다.
 *
 * 좌석별 재접속 판정은 `SessionConnectedEvent` 로 한다 - `ConnectionTimer` 가 재접속 판정에 쓰는
 * 것과 같은 이벤트다.
 *
 * `ConnectionTimer` 는 복구 유예 중인 사용자에게 평소의 연결 타이머를 걸지 않아야 한다(서버가
 * 죽어있던 시간은 플레이어 책임이 아니다). pull 방식(`ConnectionTimer` 가 `isAwaitingRecovery` 로
 * 조회)은 복구가 끝났을 때 안 돌아온 사람에게 연결 타이머를 *새로* 걸어야 하는 요구(이 컴포넌트가
 * `ConnectionTimer` 를 불러야 한다)와 합치면 순환 빈 의존이 된다. 그래서 이 컴포넌트가
 * [beginRecovery] 에서 `ConnectionTimer.suspendWatch` 로 복구 대상을 미리 알리고(push),
 * [completeRecovery] 에서 결과에 따라 `ConnectionTimer.cancelWatch`(돌아온 사람) /
 * `ConnectionTimer.beginWatch`(안 돌아온 사람) 로 반영한다. 새 포트 인터페이스는 두지 않는다 -
 * 둘 다 같은 infrastructure 계층의 구체 타입이라 직접 의존으로 충분하다.
 *
 * 첫 INSERT(=`HandStore.save` 첫 호출) 전에 죽으면 `holdem_hand_in_progress` 에 행이 아예 없다.
 * 이때 애그리거트(HoldemTable)의 좌석 스택은 핸드 시작 전 값 그대로다 - 핸드가 있었다는 흔적
 * 자체가 없으므로 "그 핸드는 없었던 것"이 이미 올바른 상태이고, 이 컴포넌트가 따로 처리할
 * 게 없다.
 *
 * **단일 인스턴스 배포를 전제한다.** 인스턴스가 여럿이면 모두가 같은 행을 복구하려 들어 재개
 * 유스케이스가 중복 호출된다. 다중 인스턴스로 가려면 리더 선출이나 행 잠금이 필요하다.
 *
 * 3분 유예는 전원이 돌아오면 일찍, 아니면 시간이 다 되어 *재개*로 끝난다(하우스 룰 8번) — 취소·환불하지 않는다.
 * 안 돌아온 사람은 [applyWatch] 로 평소의 연결 감시를 새로 받고, 재개가 거는 새 차례 타이머(30초)가
 * 그 사람의 차례를 체크/폴드·퇴장으로 처리한다. 이미 낸 칩은 팟에 남는다. 취소·환불은 재개가 끝내 실패할 때(서버 버그)만 쓴다.
 *
 * 런아웃 중인 핸드는 3분 재접속 유예 없이 바로 재개한다 — 남은 결정이 없어 기다려도 얻는 게 없고, 오히려
 * 질 쪽이 일부러 돌아오지 않아 이미 공개된 올인을 무효로 만들 수 있다. 안 돌아온 사용자는 유예 중단
 * (`suspendWatch`)을 하지 않았으므로 평소의 연결 타이머가 그대로 건다.
 *
 * 진행 중 핸드가 없고 후보(스택이 있는 점유 좌석)가 2명 이상인 테이블마다 다음 핸드 카운트다운을
 * 새로 건다 — 재시작 전에 몇 초가 남아 있었는지는 알 길이 없고(인메모리라 사라졌다) 알 필요도
 * 없다, 어차피 상대 시간이라 처음부터 다시 재도 된다. 핸드가 있으면 그 핸드가 끝날 때
 * `HandSettler` 가 걸거나, 이미 진행 중인 복구 흐름이 처리한다.
 *
 * 착석 대기열은 이제 인메모리(JoinQueue)라 여기서 훑을 게 없다 — 좌석 배정 전이라 재시작하면
 * 그냥 비워지는 게 맞는 동작이다(돈이 걸려 있지 않다).
 */
@Component
class HandRecovery(
    private val handStore: HandStore,
    private val tables: HoldemTableRepository,
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
    private val resumeHandUseCase: ResumeHandUseCase,
    private val cancelHandUseCase: CancelHandUseCase,
    private val connectionTimer: ConnectionTimer,
    private val nextHandCountdown: NextHandCountdown,
    private val tableExecutor: TableExecutor,
    private val userConnections: UserConnections,
) {
    private class PendingRecovery(
        val allUserIds: Set<UserId>,
        val awaitingUserIds: MutableSet<UserId>,
        val future: ScheduledFuture<*>,
    )

    private val pending = ConcurrentHashMap<TableId, PendingRecovery>()
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    /**
     * `ConnectionTimer.onApplicationReady`(`@Order(1)`) 보다 먼저 실행되어야 한다 - 그래야 여기서
     * `ConnectionTimer.suspendWatch` 로 걸어둔 복구 대상이 그 컴포넌트의 부팅 시딩에서 제외된다.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Order(0)
    fun onApplicationReady() {
        val tableIds = handStore.findAllInProgress()
        if (tableIds.isNotEmpty()) {
            log.info("복구 대상 진행 중 핸드 {}건을 확인했습니다: tableIds={}", tableIds.size, tableIds.map { it.value })
            tableIds.forEach { beginRecovery(it) }
        }

        armNextHandCountdowns()
    }

    private fun armNextHandCountdowns() {
        for (tableId in tables.findAllTableIds()) {
            if (handStore.find(tableId) != null) continue
            val table = tables.findById(tableId) ?: continue
            if (table.candidateSeatNos().size < 2) continue
            nextHandCountdown.restart(tableId)
            log.info("테이블 {} 다음 핸드 카운트다운을 새로 걸었습니다.", tableId.value)
        }
    }

    private fun beginRecovery(tableId: TableId) {
        val hand = handStore.find(tableId) ?: return
        val table = tables.findById(tableId) ?: return

        val allUserIds = hand.seatNos.mapNotNull { seatNo -> table.seatAt(seatNo)?.userId }.toSet()
        if (allUserIds.isEmpty()) {
            log.info("테이블 {} 복구 대상 좌석을 찾지 못해 바로 재개합니다.", tableId.value)
            resumeOrCancel(tableId)
            return
        }

        // 런아웃 중이면 남은 플레이어 결정이 없다 - 기다리면 질 쪽이 재접속을 안 해서 이미 공개된
        // 올인을 무효로 만들 수 있을 뿐이다. 유예·감시 중단 없이 바로 재개한다. 안 돌아온 사람은
        // ConnectionTimer 의 부팅 시딩(@Order(1))이 평소대로 연결 타이머를 건다(suspendWatch 를 안 했으므로).
        if (hand.isRunningOut) {
            log.info("테이블 {} 런아웃 중인 핸드라 재접속을 기다리지 않고 바로 재개합니다.", tableId.value)
            resumeOrCancel(tableId)
            return
        }

        // Tomcat 은 Ready 전에 접속을 받는다 - 이미 연결된 사람은 SessionConnectedEvent 가 다시 오지 않으므로 기다리면 안 된다.
        val awaiting = allUserIds.filterNot { userConnections.isConnected(it) }
        if (awaiting.isEmpty()) {
            log.info("테이블 {} 복구 대상 전원이 이미 연결돼 있어 바로 재개합니다.", tableId.value)
            resumeOrCancel(tableId)
            return
        }

        connectionTimer.suspendWatch(allUserIds)

        pending.remove(tableId)?.future?.cancel(false)
        val token = tokens.computeIfAbsent(tableId) { AtomicLong() }.incrementAndGet()
        val future = taskScheduler.schedule(
            { onRecoveryTimeout(tableId, token) },
            Instant.now(clock).plus(RECOVERY_TIMEOUT),
        )
        pending[tableId] = PendingRecovery(
            allUserIds = allUserIds,
            awaitingUserIds = ConcurrentHashMap.newKeySet<UserId>().apply { addAll(awaiting) },
            future = future,
        )
        log.info("테이블 {} 복구 유예(3분)를 시작합니다: 대기 사용자={}", tableId.value, allUserIds)
    }

    /** 테이블이 닫히면 복구 유예와 토큰을 치운다. 토큰이 사라지면 이미 깨어난 타임아웃도 걸러진다. */
    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        pending.remove(event.tableId)?.future?.cancel(false)
        tokens.remove(event.tableId)
    }

    @EventListener
    fun onSessionConnected(event: SessionConnectedEvent) {
        val userId = event.user?.name?.toLongOrNull()?.let(::UserId) ?: return
        for ((tableId, recovery) in pending) {
            if (recovery.awaitingUserIds.remove(userId) && recovery.awaitingUserIds.isEmpty()) {
                completeRecovery(tableId)
            }
        }
    }

    private fun onRecoveryTimeout(tableId: TableId, token: Long) {
        if (tokens[tableId]?.get() != token) return
        completeRecovery(tableId)
    }

    /** 전원이 돌아왔거나 3분이 지났다 - 어느 쪽이든 핸드를 재개한다. 안 돌아온 사람은 [applyWatch] 와 차례 타이머가 맡는다. */
    private fun completeRecovery(tableId: TableId) {
        if (!finishRecovery(tableId)) return

        log.info("테이블 {} 복구 유예가 끝나 핸드를 재개합니다.", tableId.value)
        resumeOrCancel(tableId)
    }

    /**
     * 유예 중에 핸드가 다시 살아났다(플레이어가 액션해 [HandBroadcastRequested] 가 났다) - 3분 뒤 타임아웃이
     * 그 뒤의 핸드를 또 재개하면 안 되므로 재개 없이 유예만 끝낸다. 재개 유스케이스가 내는 이벤트는
     * [completeRecovery] 가 pending 을 먼저 지운 뒤라 여기서 아무것도 못 찾는다. 커밋 후 콜백이라 던지지 않는다.
     */
    @TransactionalEventListener
    fun onHandBroadcastRequested(event: HandBroadcastRequested) {
        try {
            val recovery = removePending(event.tableId) ?: return
            log.info("테이블 {} 복구 유예 중 핸드가 다시 진행되어 유예를 끝냅니다.", event.tableId.value)
            // 이 스레드는 아직 JDBC 커넥션을 쥐고 있다 - 감시 반영은 presence 갱신(tableExecutor.call)을 부르므로
            // 인라인으로 돌면 커밋된 트랜잭션에 합류해 던진다. post 로 커넥션이 풀린 뒤 테이블 스레드에서 돈다.
            tableExecutor.post(event.tableId) { applyWatch(recovery) }
        } catch (e: Exception) {
            log.error("복구 유예 종료 처리에 실패했습니다: tableId={}", event.tableId.value, e)
        }
    }

    /** 유예를 걷어내고 감시를 반영한다. 유예 중이 아니었으면 false. */
    private fun finishRecovery(tableId: TableId): Boolean {
        applyWatch(removePending(tableId) ?: return false)
        return true
    }

    private fun removePending(tableId: TableId): PendingRecovery? {
        val recovery = pending.remove(tableId) ?: return null
        recovery.future.cancel(false)
        tokens[tableId]?.incrementAndGet()
        return recovery
    }

    private fun applyWatch(recovery: PendingRecovery) {
        // 돌아온 사람은 유예를 해제하고, 안 돌아온 사람은 지금부터 3분 연결 타이머를 새로 건다.
        // 돌아왔다가 유예 중에 다시 끊긴 사람(그 끊김은 무시됐다)도 연결이 없으니 감시를 새로 받는다.
        // 한 명의 실패가 나머지를 영영 유예 상태로 두면 안 된다 - 사용자별로 격리한다.
        val returned = recovery.allUserIds - recovery.awaitingUserIds
        for (userId in recovery.allUserIds) {
            try {
                if (userId in returned && userConnections.isConnected(userId)) connectionTimer.cancelWatch(userId)
                else connectionTimer.beginWatch(userId)
            } catch (e: Exception) {
                log.error("복구 유예 종료 뒤 연결 감시를 반영하지 못했습니다: userId={}", userId, e)
            }
        }
    }

    // 재개가 안 되면 핸드가 DB 에 남은 채 멈춘다 — 다시 걸고, 그래도 안 되면(서버 버그) 취소·환불한다(그것도 다시 건다).
    private fun resumeOrCancel(tableId: TableId) = taskScheduler.runRetrying(
        "핸드 재개 tableId=${tableId.value}", clock,
        isStale = { false },
        onGiveUp = { cancelRetrying(tableId) },
    ) { tableExecutor.call(tableId) { resumeHandUseCase.resume(ResumeHandCommand(tableId.value)) } }

    private fun cancelRetrying(tableId: TableId) = taskScheduler.runRetrying(
        "핸드 취소 tableId=${tableId.value}", clock,
        isStale = { false },
        onGiveUp = { log.error("핸드 취소·환불도 실패했습니다. 더 재시도하지 않습니다: tableId={}", tableId.value) },
    ) { tableExecutor.call(tableId) { cancelHandUseCase.cancel(CancelHandCommand(tableId.value)) } }

    companion object {
        private val log = LoggerFactory.getLogger(HandRecovery::class.java)

        // TurnTimer.TURN_TIMEOUT/ConnectionTimer.CONNECTION_TIMEOUT 과 값은 같지만 상수를 공유하지
        // 않는다 - 재는 대상이 다르다(그 둘은 개별 사용자·차례, 이건 테이블 전체의 복구 유예다).
        private val RECOVERY_TIMEOUT: Duration = Duration.ofMinutes(3)
    }
}
