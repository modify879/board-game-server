package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdvanceRunoutUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 올인 런아웃을 한 스트리트 진행한다. 호출자는 [com.jsm.boardgame.holdem.infrastructure.timer.RunoutTimer]
 * 뿐이라 실패를 알릴 대상이 없다 — 테이블/핸드가 사라졌거나 런아웃 중이 아니면(낡은 발화) 조용히 끝낸다.
 */
@Service
@Transactional
class AdvanceRunoutService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val handSettler: HandSettler,
    private val eventPublisher: ApplicationEventPublisher,
) : AdvanceRunoutUseCase {

    override fun advance(command: AdvanceRunoutCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId) ?: return
        val hand = handStore.find(tableId) ?: return
        if (!hand.isRunningOut) return

        hand.advanceRunout()

        if (hand.isFinished) {
            handSettler.settle(tableId, table, hand)
        } else {
            handStore.save(tableId, hand)
            eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
        }
    }
}
