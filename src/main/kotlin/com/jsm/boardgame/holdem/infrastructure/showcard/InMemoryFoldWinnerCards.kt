package com.jsm.boardgame.holdem.infrastructure.showcard

import com.jsm.boardgame.holdem.application.port.FoldWinnerCards
import com.jsm.boardgame.holdem.application.port.FoldWinnerCards.FoldWinner
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// ponytail: 인메모리라 재시작하면 사라진다 — 재시작 직후 그 핸드의 승자는 보여 줄 수 없을 뿐이다. 문제가 되면 DB 에 저장한다.
@Component
class InMemoryFoldWinnerCards : FoldWinnerCards {
    private val winners = ConcurrentHashMap<Long, FoldWinner>()

    override fun save(tableId: TableId, winner: FoldWinner) { winners[tableId.value] = winner }
    override fun find(tableId: TableId): FoldWinner? = winners[tableId.value]
    override fun remove(tableId: TableId) { winners.remove(tableId.value) }
}
