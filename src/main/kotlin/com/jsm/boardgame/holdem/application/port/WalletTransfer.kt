package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * holdem 이 필요로 하는 지갑 이체. 포트를 부르는 쪽(holdem)이 소유하고
 * infrastructure/acl 의 어댑터가 wallet 의 공개 application 만 부른다.
 */
interface WalletTransfer {
    /** 지갑 잔액 전부를 테이블로 옮기고 옮긴 금액을 돌려준다. 잔액이 0이면 0이다. */
    fun allToGame(userId: Long, tableId: TableId, memo: String?): Long
    fun fromGame(userId: Long, amount: Long, tableId: TableId, memo: String?)
}
