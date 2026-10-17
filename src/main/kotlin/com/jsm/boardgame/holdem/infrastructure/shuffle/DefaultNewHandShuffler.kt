package com.jsm.boardgame.holdem.infrastructure.shuffle

import com.jsm.boardgame.holdem.application.port.NewHandShuffler
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.stereotype.Component

@Component
class DefaultNewHandShuffler(private val shuffler: Shuffler) : NewHandShuffler {
    override fun shufflerFor(tableId: TableId, seatNos: List<Int>, buttonSeatNo: Int): Shuffler = shuffler
}
