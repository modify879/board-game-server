package com.jsm.boardgame.holdem.application.port

/**
 * holdem 이 필요로 하는 지갑 이체. 포트를 부르는 쪽(holdem)이 소유하고
 * infrastructure/acl 의 어댑터가 wallet 의 공개 application 만 부른다.
 */
interface WalletTransfer {
    fun toGame(userId: Long, amount: Long, tableId: Long, memo: String?)
    fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?)
}
