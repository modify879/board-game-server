package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ShowHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.ShowHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.ShowNotAllowedException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.FoldWinnerCards
import com.jsm.boardgame.holdem.application.port.HandHistory
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.HandEvaluator
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 하우스 룰 10 — 모두 폴드해 이긴 사람만, 핸드가 끝난 뒤 다음 핸드 전에 두 장을 공개한다. 한 번만 가능하다.
 * 히스토리의 shown 표시도 같은 트랜잭션에서 올린다. 족보 이름은 보드가 3장 이상일 때만 붙인다(프리플랍 폴드 승리는 null).
 */
@Service
@Transactional
class ShowHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val foldWinnerCards: FoldWinnerCards,
    private val lastHandResults: LastHandResultStore,
    private val handHistory: HandHistory,
    private val eventPublisher: ApplicationEventPublisher,
) : ShowHandUseCase {

    override fun show(command: ShowHandCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId)
            ?: throw TableNotFoundException("테이블을 찾을 수 없습니다: tableId=${command.tableId}")
        if (handStore.find(tableId) != null) {
            throw HandInProgressException("핸드가 진행 중이라 보여 줄 수 없습니다: tableId=${command.tableId}")
        }
        val winner = foldWinnerCards.find(tableId)?.takeIf { it.userId == UserId(command.userId) }
            ?: throw ShowNotAllowedException("보여 줄 수 있는 폴드 승자가 아닙니다: tableId=${command.tableId}, userId=${command.userId}")
        val last = lastHandResults.find(tableId)
            ?: throw ShowNotAllowedException("직전 핸드 결과가 없습니다: tableId=${command.tableId}")

        val category = if (last.board.size >= 3) {
            HandEvaluator.evaluate((winner.holeCards + last.board).map(Card::of)).category.name
        } else {
            null
        }
        lastHandResults.save(
            tableId,
            last.copy(shownHands = last.shownHands + LastHandResult.ShownHand(winner.seatNo, winner.userId, winner.holeCards, category)),
        )
        foldWinnerCards.remove(tableId)
        handHistory.markShown(tableId, winner.userId)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
    }
}
