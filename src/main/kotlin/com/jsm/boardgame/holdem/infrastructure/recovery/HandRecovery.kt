package com.jsm.boardgame.holdem.infrastructure.recovery

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
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
 * 죽어있던 시간은 플레이어 책임이 아니다) - 그렇다고 새 포트 인터페이스를 두지 않는다. 이
 * 컴포넌트가 [isAwaitingRecovery] 를 공개 메서드로 노출하고, `ConnectionTimer` 가 자기 연결
 * 끊김 처리 전에 그걸 물어보고 참이면 건너뛰는 방식으로 충분하다 - 구현체 하나짜리 포트를
 * 만드는 대신 같은 infrastructure 계층 안에서 구체 타입으로 직접 의존한다. [isAwaitingRecovery] 는
 * "아직 재접속을 안 한" 좌석뿐 아니라 그 테이블 복구에 참가한 전체 좌석을 유예 기간 내내
 * 가려준다 - 이미 재접속했다가 대기 중에 연결이 한 번 더 끊겨도, 그 유예 기간 전체가 서버
 * 다운타임의 연장선이라 개인 책임이 아니기 때문이다.
 *
 * 첫 INSERT(=`HandStore.save` 첫 호출) 전에 죽으면 `holdem_hand_in_progress` 에 행이 아예 없다.
 * 이때 애그리거트(HoldemTable)의 좌석 스택은 핸드 시작 전 값 그대로다 - 핸드가 있었다는 흔적
 * 자체가 없으므로 "그 핸드는 없었던 것"이 이미 올바른 상태이고, 이 컴포넌트가 따로 처리할
 * 게 없다.
 *
 * ponytail: 단일 인스턴스 전제다. 인스턴스가 둘 이상이면 모두 같은 행을 복구하려 들어
 * [CancelHandUseCase]/[ResumeHandUseCase] 가 중복 호출된다 - 리더 선출이나 행 잠금이 필요해지면
 * 그때 다시 본다.
 */
@Component
class HandRecovery(
    private val handStore: HandStore,
    private val tables: HoldemTableRepository,
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
    private val resumeHandUseCase: ResumeHandUseCase,
    private val cancelHandUseCase: CancelHandUseCase,
) {
    private class PendingRecovery(
        val allUserIds: Set<Long>,
        val awaitingUserIds: MutableSet<Long>,
        val future: ScheduledFuture<*>,
    )

    private val pending = ConcurrentHashMap<TableId, PendingRecovery>()
    private val tokens = ConcurrentHashMap<TableId, AtomicLong>()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        val tableIds = handStore.findAllInProgress()
        if (tableIds.isEmpty()) return

        log.info("복구 대상 진행 중 핸드 {}건을 확인했습니다: tableIds={}", tableIds.size, tableIds.map { it.value })
        tableIds.forEach { beginRecovery(it) }
    }

    private fun beginRecovery(tableId: TableId) {
        val hand = handStore.find(tableId) ?: return
        val table = tables.findById(tableId) ?: return

        val allUserIds = hand.seatNos.mapNotNull { seatNo -> table.seatAt(seatNo)?.userId }.toSet()
        if (allUserIds.isEmpty()) {
            log.info("테이블 {} 복구 대상 좌석을 찾지 못해 바로 재개합니다.", tableId.value)
            resumeHandUseCase.resume(ResumeHandCommand(tableId.value))
            return
        }

        pending.remove(tableId)?.future?.cancel(false)
        val token = tokens.computeIfAbsent(tableId) { AtomicLong() }.incrementAndGet()
        val future = taskScheduler.schedule(
            { onRecoveryTimeout(tableId, token) },
            Instant.now(clock).plus(RECOVERY_TIMEOUT),
        )
        pending[tableId] = PendingRecovery(
            allUserIds = allUserIds,
            awaitingUserIds = ConcurrentHashMap.newKeySet<Long>().apply { addAll(allUserIds) },
            future = future,
        )
        log.info("테이블 {} 복구 유예(3분)를 시작합니다: 대기 사용자={}", tableId.value, allUserIds)
    }

    @EventListener
    fun onSessionConnected(event: SessionConnectedEvent) {
        val userId = event.user?.name?.toLongOrNull() ?: return
        for ((tableId, recovery) in pending) {
            if (recovery.awaitingUserIds.remove(userId) && recovery.awaitingUserIds.isEmpty()) {
                completeRecovery(tableId, resume = true)
            }
        }
    }

    private fun onRecoveryTimeout(tableId: TableId, token: Long) {
        if (tokens[tableId]?.get() != token) return
        completeRecovery(tableId, resume = false)
    }

    private fun completeRecovery(tableId: TableId, resume: Boolean) {
        val recovery = pending.remove(tableId) ?: return
        recovery.future.cancel(false)
        if (resume) {
            log.info("테이블 {} 전원 재접속을 확인해 핸드를 재개합니다.", tableId.value)
            resumeHandUseCase.resume(ResumeHandCommand(tableId.value))
        } else {
            log.info("테이블 {} 복구 유예 시간 안에 전원이 돌아오지 않아 핸드를 취소합니다.", tableId.value)
            cancelHandUseCase.cancel(CancelHandCommand(tableId.value))
        }
    }

    /** `ConnectionTimer` 가 연결 끊김을 처리하기 전에 묻는다. */
    fun isAwaitingRecovery(userId: Long): Boolean = pending.values.any { userId in it.allUserIds }

    companion object {
        private val log = LoggerFactory.getLogger(HandRecovery::class.java)

        // TurnTimer.TURN_TIMEOUT/ConnectionTimer.CONNECTION_TIMEOUT 과 값은 같지만 상수를 공유하지
        // 않는다 - 재는 대상이 다르다(그 둘은 개별 사용자·차례, 이건 테이블 전체의 복구 유예다).
        private val RECOVERY_TIMEOUT: Duration = Duration.ofMinutes(3)
    }
}
