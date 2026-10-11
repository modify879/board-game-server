package com.jsm.boardgame.holdem.application.query.view

import java.time.Instant

/** maxStack 은 칩 추가로 채울 수 있는 스택 상한(100BB)이다. 재착석 의무가 있으면 minBuyIn 이 올라가고 maxBuyIn 도 따라 올라갈 수 있다. */
data class BuyInView(
    val minBuyIn: Long,
    val maxBuyIn: Long,
    val maxStack: Long,
    val walletBalance: Long,
    val obligationExpiresAt: Instant?,
)
