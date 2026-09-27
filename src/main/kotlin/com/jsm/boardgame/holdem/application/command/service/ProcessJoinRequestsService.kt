package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestResult
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsUseCase
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 대기열 맨 앞부터 순서대로 착석을 시도한다. 이 클래스 자체는 @Transactional 이 아니다 — 항목
 * 하나의 실패가 다른 항목 처리에 영향을 주지 않게 하는 것이 AdmitJoinRequestService 의
 * REQUIRES_NEW 격리 목적이라, 이 메서드 전체를 하나의 트랜잭션으로 묶으면 그 목적이 무의미해진다.
 *
 * 테이블별로 JVM 락(블로킹, tryLock 아님)으로 직렬화한다 — tryLock 이면 처리 루프가 막 빠져나가는
 * 순간 들어온 새 항목이 이번에도, 다음 트리거가 올 때까지도 처리되지 않고 방치될 수 있다.
 * 단일 인스턴스 배포를 전제한다(InMemoryJoinQueue 와 같은 전제).
 */
@Service
class ProcessJoinRequestsService(
    private val joinQueue: JoinQueue,
    private val admitJoinRequestUseCase: AdmitJoinRequestUseCase,
    private val notifier: JoinQueueNotifier,
) : ProcessJoinRequestsUseCase {

    // ponytail: 정리되지 않는다 — 테이블은 몇 개뿐이고 삭제되지 않아, 엔트리가 쌓여도 무해하다.
    private val tableLocks = ConcurrentHashMap<Long, Any>()

    override fun process(command: ProcessJoinRequestsCommand) {
        val tableId = TableId(command.tableId)
        synchronized(tableLocks.computeIfAbsent(command.tableId) { Any() }) {
            while (true) {
                val head = joinQueue.peekHead(tableId) ?: return

                try {
                    when (
                        val result = admitJoinRequestUseCase.admit(
                            AdmitJoinRequestCommand(tableId.value, head.userId, head.buyIn.amount, head.postBlindImmediately),
                        )
                    ) {
                        is AdmitJoinRequestResult.Blocked -> return
                        is AdmitJoinRequestResult.Seated -> {
                            joinQueue.removeByUserId(head.userId)
                            notifier.notifySeated(tableId, head.userId, result.seatNo)
                            notifier.notifyPositions(tableId, joinQueue.entriesOf(tableId))
                        }
                    }
                } catch (e: ConcurrentTableUpdateException) {
                    // ponytail: 무제한 재시도다 — 경합은 일시적이라고 가정한다. 지속적인 경합이 실제로
                    // 관찰되면 재시도 횟수 상한을 둔다.
                    log.info("대기열 처리가 경합해 다시 시도합니다: tableId={}, userId={}", tableId.value, head.userId)
                    continue
                } catch (e: BusinessException) {
                    log.info(
                        "대기열 항목을 처리하지 못해 버립니다: tableId={}, userId={}, errorCode={}",
                        tableId.value, head.userId, e.errorCode,
                    )
                    joinQueue.removeByUserId(head.userId)
                    notifier.notifyDropped(tableId, head.userId, e.errorCode)
                    notifier.notifyPositions(tableId, joinQueue.entriesOf(tableId))
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProcessJoinRequestsService::class.java)
    }
}
