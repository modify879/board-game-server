package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceCommand
import com.jsm.boardgame.holdem.application.command.usecase.UpdateSeatPresenceUseCase
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 좌석의 연결 상태 표시만 바꾼다. 호출자는 사용자가 아니라 ConnectionTimer(웹소켓 세션 이벤트)라
 * 미착석 사용자의 이벤트는 알릴 대상이 없어 조용히 끝낸다.
 */
@Service
@Transactional
class UpdateSeatPresenceService(
    private val tables: HoldemTableRepository,
) : UpdateSeatPresenceUseCase {

    override fun update(command: UpdateSeatPresenceCommand) {
        val table = tables.findByUserId(command.userId) ?: return
        table.markPresence(command.userId, SeatPresence.valueOf(command.presence))
        tables.save(table)
    }
}
