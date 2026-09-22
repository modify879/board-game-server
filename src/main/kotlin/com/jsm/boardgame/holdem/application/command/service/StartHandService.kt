package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 테이블의 점유 좌석 중 스택이 양수인 좌석만 핸드에 넣는다. 버튼은 점유 좌석 기준으로 옮기므로
 * 0칩 좌석에 놓일 수 있다 — 그때는 참가 좌석 중 다음 좌석으로 한 번 더 옮긴다.
 *
 * ponytail: 정식 dead button 규칙(블라인드를 한 바퀴 보장)은 아니다. 버튼이 스킵된 0칩 좌석은
 * 다음 핸드에서 스몰/빅 블라인드를 한 번 덜 낼 수 있다.
 */
@Service
@Transactional
class StartHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val shuffler: Shuffler,
    private val handSettler: HandSettler,
    private val eventPublisher: ApplicationEventPublisher,
) : StartHandUseCase {

    override fun start(command: StartHandCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId)
            ?: throw TableNotFoundException("테이블을 찾을 수 없습니다: tableId=${command.tableId}")

        if (handStore.find(tableId) != null) {
            throw HandInProgressException("이미 진행 중인 핸드가 있습니다: tableId=${command.tableId}")
        }

        val participatingSeatNos = table.occupiedSeats().filter { it.stack.isPositive() }.map { it.seatNo }.toSet()
        if (participatingSeatNos.size < 2) {
            throw IllegalHandStateException(
                HoldemErrorCode.NOT_ENOUGH_PLAYERS,
                "스택이 있는 참가자가 2명 미만입니다: tableId=${command.tableId}, participants=$participatingSeatNos",
            )
        }

        table.moveButtonToNextOccupiedSeat()
        val occupiedCount = table.occupiedSeats().size
        var attempts = 0
        while (table.buttonSeatNo !in participatingSeatNos && attempts < occupiedCount) {
            table.moveButtonToNextOccupiedSeat()
            attempts++
        }

        val stacks = participatingSeatNos.associateWith { seatNo -> table.seatAt(seatNo)!!.stack }
        val hand = Hand.start(stacks, table.buttonSeatNo!!, table.smallBlind, table.bigBlind, shuffler)

        if (hand.isFinished) {
            handSettler.settle(tableId, table, hand)
            return
        }

        handStore.save(tableId, hand)
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
    }
}
