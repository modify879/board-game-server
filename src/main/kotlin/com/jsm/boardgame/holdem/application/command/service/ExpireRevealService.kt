package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireRevealCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireRevealUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 공개 선택 제한시간(`app.holdem.reveal-timeout`) 만료 처리. 호출자는
 * [com.jsm.boardgame.holdem.infrastructure.timer.RevealTimer] 뿐이라 실패를 알릴 대상이 없다 —
 * 창이 이미 닫혔으면(전원 결정·새 핸드 시작 등) 조용히 return 한다.
 *
 * nextHandAt 은 건드리지 않는다 — [HandSettler.settle] 이 창을 열 때 이미 만료 시각까지 반영해
 * 예약해 두었다(`revealDeadline + nextHandDelay`).
 */
@Service
@Transactional
class ExpireRevealService(
    private val tables: HoldemTableRepository,
    private val showdownStore: ShowdownStore,
    private val eventPublisher: ApplicationEventPublisher,
) : ExpireRevealUseCase {

    override fun expire(command: ExpireRevealCommand) {
        val tableId = TableId(command.tableId)
        val open = showdownStore.find(tableId) ?: return

        synchronized(open) { open.hand.muckPendingReveals() }

        showdownStore.remove(tableId)

        val table = tables.findById(tableId) ?: return
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, open.hand))
    }
}
