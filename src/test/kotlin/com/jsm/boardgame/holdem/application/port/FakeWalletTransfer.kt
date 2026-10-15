package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId

/** 서비스 테스트가 공유하는 인메모리 대역. 부른 이체를 기록한다. */
class FakeWalletTransfer : WalletTransfer {
    data class Call(val userId: UserId, val amount: Long, val tableId: TableId, val memo: String?)

    val fromGameCalls = mutableListOf<Call>()

    override fun toGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        error("toGame 은 부르지 않는다")
    }

    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        fromGameCalls += Call(userId, amount, tableId, memo)
    }
}
