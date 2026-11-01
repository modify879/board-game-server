package com.jsm.boardgame.holdem.application.command.support

import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import org.springframework.stereotype.Component

/**
 * 기립의 돈 처리를 한 곳에서 한다 - 재착석 의무를 기록하고, 양수 스택만 지갑으로 돌려보낸다(0칩이면 지갑을 부르지 않는다).
 * 호출자의 트랜잭션 안에서 돈다.
 */
@Component
class SeatDeparture(
    private val recentDepartures: RecentDepartures,
    private val walletTransfer: WalletTransfer,
) {
    fun standUp(tableId: TableId, table: HoldemTable, userId: UserId): Chips {
        val returned = table.standUp(userId)
        recentDepartures.record(userId, tableId, returned)
        if (returned.isPositive()) {
            // memo 가 유일하게 "어느 게임인가" 를 나른다 — LedgerEntryType 이 이미 방향을 말하고
            // LedgerReferenceType.GAME_TABLE 엔 게임 이름이 없다. 표시 문구를 넣지 않는다(규칙 8, 문구는 클라이언트가 만든다).
            walletTransfer.fromGame(userId, returned.amount, tableId, memo = "holdem")
        }
        return returned
    }
}
