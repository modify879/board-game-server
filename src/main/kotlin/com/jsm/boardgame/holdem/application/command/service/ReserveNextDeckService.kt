package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ReserveNextDeckCommand
import com.jsm.boardgame.holdem.application.command.usecase.ReserveNextDeckUseCase
import com.jsm.boardgame.holdem.application.port.NextDeckReserver
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("qa")
class ReserveNextDeckService(private val reserver: NextDeckReserver) : ReserveNextDeckUseCase {

    override fun reserve(command: ReserveNextDeckCommand) {
        reserver.reserve(
            TableId(command.tableId),
            command.holeCards.mapValues { (_, cards) -> cards.map(Card::of) },
            command.board.map(Card::of),
        )
    }
}
