package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.RevealHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.RevealHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.UnknownActionException
import com.jsm.boardgame.holdem.application.port.ShowdownStore
import com.jsm.boardgame.holdem.domain.exception.RevealNotAllowedException
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 쇼다운에서 진 좌석의 SHOW/MUCK 선택(사용자 결정) — 승자는 자동 공개라 이 경로를 타지 않는다.
 *
 * 선택은 [com.jsm.boardgame.holdem.infrastructure.timer.RevealTimer] 의 제한시간 만료 처리
 * ([ExpireRevealService])와 같은 [com.jsm.boardgame.holdem.domain.model.Hand] 인스턴스를 두고
 * 경합한다 — `synchronized(open)` 으로 막는다.
 * // ponytail: 단일 인스턴스 전제의 모니터 락. 인스턴스가 여럿이면 분산 락으로 바꿔야 한다.
 */
@Service
@Transactional
class RevealHandService(
    private val tables: HoldemTableRepository,
    private val showdownStore: ShowdownStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${app.holdem.next-hand-delay}") private val nextHandDelay: Duration,
) : RevealHandUseCase {

    override fun reveal(command: RevealHandCommand) {
        val show = toShow(command.action)
        val tableId = TableId(command.tableId)
        val open = showdownStore.find(tableId)
            ?: throw RevealNotAllowedException("공개 선택 창이 열려 있지 않습니다: tableId=${command.tableId}")
        val seatNo = open.seatNoByUserId[command.userId]
            ?: throw RevealNotAllowedException(
                "공개를 선택할 수 있는 좌석이 아닙니다: tableId=${command.tableId}, userId=${command.userId}",
            )

        val allDecided = synchronized(open) {
            open.hand.reveal(seatNo, show)
            open.hand.awaitingRevealSeatNos.isEmpty()
        }

        val table = tables.findById(tableId)
            ?: error("공개 선택 창이 열려 있는데 테이블을 찾을 수 없습니다: tableId=${command.tableId}")

        if (allDecided) {
            showdownStore.remove(tableId)
            if (table.nextHandAt != null) {
                table.scheduleNextHand(Instant.now(clock).plus(nextHandDelay))
                tables.save(table)
            }
        }

        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, open.hand))
    }

    private fun toShow(action: String): Boolean = when (action) {
        "SHOW" -> true
        "MUCK" -> false
        else -> throw UnknownActionException("알 수 없는 액션입니다: $action")
    }
}
