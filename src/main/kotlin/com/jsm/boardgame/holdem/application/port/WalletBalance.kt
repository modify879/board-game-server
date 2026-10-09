package com.jsm.boardgame.holdem.application.port

/** holdem 이 필요로 하는 지갑 잔액 조회. 착석 요청 시 친절한 사전 오류용이다(실제 보장은 이체 시점). */
interface WalletBalance {
    fun balanceOf(userId: Long): Long
}
