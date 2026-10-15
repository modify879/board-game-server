package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelLeaveReservationCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelLeaveReservationUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 퇴장 예약 취소. 예약이 있었을 때만 저장하고 공개 뷰를 브로드캐스트한다. */
@Service
@Transactional
class CancelLeaveReservationService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val eventPublisher: ApplicationEventPublisher,
) : CancelLeaveReservationUseCase {

    override fun cancel(command: CancelLeaveReservationCommand) {
        val userId = UserId(command.userId)
        val table = tables.findByUserId(userId)
            ?: throw NotSeatedException("어느 테이블에도 앉아 있지 않은 사용자입니다: userId=${command.userId}")
        if (!table.seatOf(userId)!!.leaveReserved) return
        table.cancelLeaveReservation(userId)
        tables.save(table)
        val tableId = table.id!!
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, handStore.find(tableId)))
    }
}
