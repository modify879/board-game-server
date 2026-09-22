package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 재시작 복구가 전원 재접속에 실패했을 때 진행 중 핸드를 무른다. 호출자는 사용자가 아니라
 * `HandRecovery` 뿐이다.
 *
 * 되돌리는 값은 `HandSnapshot.startingStacks` 다 - 핸드 도중 오간 칩은 애초에 `HoldemTable` 의
 * 좌석 스택에 반영되지 않는다(정산은 항상 [HandSettler.settle] 을 거쳐야 좌석에 반영된다). 그래서
 * 이 복원은 대개 이미 있는 값을 그대로 다시 쓰는 것과 같지만, 그 사실에 기대지 않고 명시적으로
 * `hand.stackOf()` 가 아니라 시작 스택으로 되돌린다 - 진행 중 핸드가 좌석 스택에 값을 쓰는 경로가
 * 나중에 생기더라도 이 취소 로직은 그대로 안전하다.
 *
 * 진행 중 핸드가 없으면(이미 정산됐거나 이미 취소됐으면) 조용히 끝난다 - 복구 절차는 죽었다
 * 다시 떠도 같은 행을 다시 탈 수 있어야 해서 멱등해야 한다.
 *
 * 좌석은 그대로 둔다 - 취소는 핸드를 무르는 것이지 사람을 내보내는 게 아니다. 안 돌아온 사람은
 * 이 서비스가 끝난 뒤 평소의 연결 타이머가 새로 돌면서 퇴장 여부를 가른다.
 */
@Service
@Transactional
class CancelHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val eventPublisher: ApplicationEventPublisher,
) : CancelHandUseCase {

    override fun cancel(command: CancelHandCommand) {
        val tableId = TableId(command.tableId)
        val hand = handStore.find(tableId) ?: return
        val table = tables.findById(tableId) ?: return

        val refunds = hand.snapshot().startingStacks
        table.applyStacks(refunds)
        tables.save(table)
        handStore.remove(tableId)

        log.info("복구 실패로 핸드를 취소하고 환불했습니다: tableId={}, refunds={}", command.tableId, refunds)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
    }

    companion object {
        private val log = LoggerFactory.getLogger(CancelHandService::class.java)
    }
}
