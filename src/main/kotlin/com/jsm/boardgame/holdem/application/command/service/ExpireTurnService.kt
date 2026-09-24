package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 차례 시간(1분) 만료 처리. 호출자는 사용자가 아니라
 * [com.jsm.boardgame.holdem.infrastructure.timer.TurnTimer] 뿐이라 실패를 알릴 대상이 없다 -
 * 테이블/핸드가 이미 사라졌거나 차례가 이미 넘어갔으면 조용히 끝낸다.
 *
 * 1분 무응답 = 자리 비움. 체크할 수 있으면 체크(자리 유지), 아니면 폴드하고 즉시 기립(관전).
 * 공식 룰(시간 초과 시 패는 죽는다)보다 체크 쪽이 관대한 것은 사용자 결정이다.
 *
 * 폴드는 여기서 즉시 기립까지 이어진다 - StandUpService 를 그대로 부르지 않는 이유는 그 서비스의
 * 계약이 "핸드 진행 중이면 거부"(going south 방지)라서다. 여기는 정반대로 "폴드했기 때문에
 * 핸드 도중에도 기립시켜야 하는" 예외 경로다. 폴드한 좌석의 스택은 이 핸드가 끝날 때까지 더
 * 바뀌지 않으므로(HandSettler.settle 이 정산에서 이 좌석을 건너뛴다) 지금 확정해서 테이블에
 * 반영하고 내보내도 안전하다.
 */
@Service
@Transactional
class ExpireTurnService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val handSettler: HandSettler,
    private val walletTransfer: WalletTransfer,
    private val eventPublisher: ApplicationEventPublisher,
) : ExpireTurnUseCase {

    override fun expire(command: ExpireTurnCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId) ?: return
        val hand = handStore.find(tableId) ?: return
        val seatNo = command.seatNo
        if (hand.toActSeatNo != seatNo) return

        val actions = hand.availableActionsFor(seatNo)
        if (actions?.canCheck == true) {
            hand.act(seatNo, BettingAction.Check)
            log.info("차례 시간 만료로 체크 처리했습니다: tableId={}, seatNo={}", command.tableId, seatNo)
        } else {
            hand.act(seatNo, BettingAction.Fold)
            log.info("차례 시간 만료로 폴드 처리하고 즉시 기립시킵니다: tableId={}, seatNo={}", command.tableId, seatNo)
            standUpFoldedSeat(table, tableId, seatNo, hand)
        }

        if (hand.isFinished) {
            handSettler.settle(tableId, table, hand)
        } else {
            handStore.save(tableId, hand)
            tables.save(table)
            eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
        }
    }

    private fun standUpFoldedSeat(table: HoldemTable, tableId: TableId, seatNo: Int, hand: Hand) {
        val userId = table.seatAt(seatNo)?.userId ?: return
        val remaining = hand.stackOf(seatNo)
        table.applyStacks(mapOf(seatNo to remaining))
        val returned = table.standUp(userId)
        if (returned.isPositive()) {
            // memo 규칙은 StandUpService 와 같다 - 문구는 클라이언트가 만든다(규칙 8).
            walletTransfer.fromGame(userId, returned.amount, tableId.value, memo = "holdem")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpireTurnService::class.java)
    }
}
