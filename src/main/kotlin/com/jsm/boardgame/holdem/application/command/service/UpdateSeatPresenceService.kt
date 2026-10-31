package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceUseCase
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.repository.HandStore
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 좌석의 연결 상태 표시만 바꾸고, 실제로 바뀌었을 때만 표시 전용 브로드캐스트를 낸다. 호출자는 사용자가 아니라 ConnectionTimer(웹소켓 세션 이벤트)라
 * 미착석 사용자의 이벤트는 알릴 대상이 없어 조용히 끝낸다.
 */
@Service
@Transactional
class UpdateSeatPresenceService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val eventPublisher: ApplicationEventPublisher,
) : UpdateSeatPresenceUseCase {

    override fun update(command: UpdateSeatPresenceCommand) {
        val table = tables.findByUserId(UserId(command.userId)) ?: return
        val userId = UserId(command.userId)
        val presence = SeatPresence.of(command.presence)
        if (table.seatOf(userId)!!.presence == presence) return
        table.markPresence(userId, presence)
        tables.save(table)
        val tableId = table.id!!
        // 표시만 바뀐 브로드캐스트 — 다른 클라이언트가 다음 상태 변화를 기다리지 않고 바로 본다.
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, handStore.find(tableId), handProgressed = false))
    }
}
