package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 차례 시간 만료 처리. 호출자는 사용자가 아니라
 * [com.jsm.boardgame.holdem.infrastructure.timer.TurnTimer] 뿐이라 실패를 알릴 대상이 없다 -
 * 테이블/핸드가 이미 사라졌거나 차례가 이미 넘어갔으면 조용히 끝낸다.
 *
 * 만료 시 처리는 폴드로 고정한다(계획서 명세). 실제 포커룸은 보통 "체크 가능하면 체크"지만,
 * 여기서는 액션 없음을 명확한 손해로 만들어 방치를 억제한다 - 바꾸려면 이 결정부터 다시 본다.
 */
@Service
@Transactional
class ExpireTurnService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val handSettler: HandSettler,
    private val eventPublisher: ApplicationEventPublisher,
) : ExpireTurnUseCase {

    override fun expire(command: ExpireTurnCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId) ?: return
        val hand = handStore.find(tableId) ?: return
        if (hand.toActSeatNo != command.seatNo) return

        hand.act(command.seatNo, BettingAction.Fold)
        log.info("차례 시간 만료로 폴드 처리했습니다: tableId={}, seatNo={}", command.tableId, command.seatNo)

        if (hand.isFinished) {
            handSettler.settle(tableId, table, hand)
        } else {
            handStore.save(tableId, hand)
            eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpireTurnService::class.java)
    }
}
