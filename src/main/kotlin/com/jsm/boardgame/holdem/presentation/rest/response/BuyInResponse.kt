package com.jsm.boardgame.holdem.presentation.rest.response

import com.jsm.boardgame.holdem.application.query.view.BuyInView
import java.time.Instant

data class BuyInResponse(
    val minBuyIn: Long,
    val maxBuyIn: Long,
    val maxStack: Long,
    val walletBalance: Long,
    val obligationExpiresAt: Instant?,
) {
    companion object {
        fun from(view: BuyInView): BuyInResponse =
            BuyInResponse(view.minBuyIn, view.maxBuyIn, view.maxStack, view.walletBalance, view.obligationExpiresAt)
    }
}
