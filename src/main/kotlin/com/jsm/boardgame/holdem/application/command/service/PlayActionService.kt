package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.PlayActionCommand
import com.jsm.boardgame.holdem.application.command.usecase.PlayActionUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.HandNotFoundException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.exception.UnknownActionException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * action 문자열 → 도메인 [BettingAction] 변환은 여기서 한다(Command 는 원시 타입만 받는다).
 * 차례·체크 가능 여부·최소 레이즈 같은 베팅 규칙은 [com.jsm.boardgame.holdem.domain.model.Hand.act] 가
 * 판정한다 — 여기서 중복 검사하지 않는다.
 */
@Service
@Transactional
class PlayActionService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val handSettler: HandSettler,
    private val eventPublisher: ApplicationEventPublisher,
) : PlayActionUseCase {

    override fun play(command: PlayActionCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId)
            ?: throw TableNotFoundException("테이블을 찾을 수 없습니다: tableId=${command.tableId}")
        val hand = handStore.find(tableId)
            ?: throw HandNotFoundException("진행 중인 핸드가 없습니다: tableId=${command.tableId}")
        val seat = table.seatOf(command.userId)
            ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=${command.userId}")

        hand.act(seat.seatNo, toBettingAction(command.action, command.raiseToAmount))

        if (hand.isFinished) {
            handSettler.settle(tableId, table, hand)
        } else {
            handStore.save(tableId, hand)
            eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
        }
    }

    private fun toBettingAction(action: String, raiseToAmount: Long?): BettingAction = when (action) {
        "FOLD" -> BettingAction.Fold
        "CHECK" -> BettingAction.Check
        "CALL" -> BettingAction.Call
        "RAISE_TO" -> BettingAction.RaiseTo(
            Chips.of(raiseToAmount ?: throw UnknownActionException("RAISE_TO 는 raiseToAmount 가 필요합니다")),
        )
        else -> throw UnknownActionException("알 수 없는 액션입니다: $action")
    }
}
