package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.wallet.application.query.service.WalletQueryService
import org.springframework.stereotype.Component

@Component
class WalletBalanceAdapter(
    private val walletQuery: WalletQueryService,
) : WalletBalance {
    override fun balanceOf(userId: UserId): Long = walletQuery.findBalance(userId.value).balance
}
