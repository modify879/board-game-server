package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ReserveNextDeckCommand
import com.jsm.boardgame.holdem.application.command.usecase.ReserveNextDeckUseCase
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.NextDeckReserver
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("qa")
class ReserveNextDeckService(
    private val reserver: NextDeckReserver,
    private val tables: HoldemTableRepository,
) : ReserveNextDeckUseCase {

    override fun reserve(command: ReserveNextDeckCommand) {
        // 없는 테이블에 예약하면 TableClosed 가 영영 오지 않아 항목이 남는다.
        if (!tables.existsOpen(TableId(command.tableId))) {
            throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=${command.tableId}")
        }
        reserver.reserve(
            TableId(command.tableId),
            command.holeCards.mapValues { (_, cards) -> cards.map(Card::of) },
            command.board.map(Card::of),
        )
    }
}
