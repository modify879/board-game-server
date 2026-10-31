package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ApplyChipsReservationsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ApplyChipsReservationsUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ApplySeatChipsReservationCommand
import com.jsm.boardgame.holdem.application.command.usecase.ApplySeatChipsReservationUseCase
import com.jsm.boardgame.holdem.domain.repository.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 핸드가 끝난 테이블의 칩 추가 예약을 좌석마다 적용한다. 이 클래스 자체는 @Transactional 이 아니다 —
 * 지갑 이체 실패가 정산 트랜잭션이나 다른 좌석의 적용을 롤백하지 않게 좌석마다 [ApplySeatChipsReservationUseCase]
 * (REQUIRES_NEW)로 격리한다([ProcessJoinRequestsService] 와 같은 구조). 직렬화는 호출자
 * ([com.jsm.boardgame.holdem.infrastructure.event.ChipsReservationsProcessor])가 테이블 실행기에 올려서 한다.
 *
 * 적용이 던지면 그 좌석은 예약만 버린다(필요하면 0칩 기립) — 실패한 예약을 남기면 0칩 좌석이 영영 앉아 있게 된다.
 */
@Service
class ApplyChipsReservationsService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val applySeat: ApplySeatChipsReservationUseCase,
) : ApplyChipsReservationsUseCase {

    override fun apply(command: ApplyChipsReservationsCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId) ?: return
        if (handStore.findInProgress(tableId) != null) return

        for (seat in table.occupiedSeats().filter { it.chipsReservation != null }) {
            val seatCommand = ApplySeatChipsReservationCommand(command.tableId, seat.userId.value)
            try {
                applySeat.apply(seatCommand)
            } catch (e: Exception) {
                log.warn("칩 추가 예약을 적용하지 못해 예약을 버립니다: tableId={}, userId={}", command.tableId, seat.userId.value, e)
                applySeat.drop(seatCommand)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ApplyChipsReservationsService::class.java)
    }
}
