package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * holdem 이 필요로 하는 지갑 이체. 포트를 부르는 쪽(holdem)이 소유하고
 * infrastructure/acl 의 어댑터가 wallet 의 공개 application 만 부른다.
 */
interface WalletTransfer {
    /** 지갑에서 [amount] 를 테이블로 옮긴다. 잔액이 모자라면 wallet 의 INSUFFICIENT_BALANCE 로 실패한다. */
    fun toGame(userId: UserId, amount: Long, tableId: TableId, memo: String?)
    fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?)
}
