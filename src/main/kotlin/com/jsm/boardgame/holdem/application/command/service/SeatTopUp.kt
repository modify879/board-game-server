package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 착석 중 지갑에 들어온 돈을 그 좌석 스택에 합친다. 핸드 도중에는 부르지 않는다(table stakes).
 * 락 순서는 테이블 → 지갑이다 — 호출자가 이미 테이블을 잠갔다. 합쳤으면 true.
 */
internal fun WalletTransfer.topUpSeat(tableId: TableId, table: HoldemTable, seatNo: Int, userId: UserId): Boolean {
    val added = allToGame(userId, tableId, memo = "holdem")
    if (added <= 0) return false
    table.topUp(seatNo, Chips.of(added))
    return true
}

internal fun WalletTransfer.topUpOccupiedSeats(tableId: TableId, table: HoldemTable) {
    table.occupiedSeats().forEach { topUpSeat(tableId, table, it.seatNo, it.userId) }
}
