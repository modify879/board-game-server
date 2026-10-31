package com.jsm.boardgame.user.infrastructure.acl

import com.jsm.boardgame.user.application.port.WalletHoldings
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.wallet.application.query.service.DepositRequestQueryService
import com.jsm.boardgame.wallet.application.query.service.WalletQueryService
import com.jsm.boardgame.wallet.application.query.service.WithdrawalRequestQueryService
import org.springframework.stereotype.Component

@Component
class WalletHoldingsAdapter(
    private val wallet: WalletQueryService,
    private val deposits: DepositRequestQueryService,
    private val withdrawals: WithdrawalRequestQueryService,
) : WalletHoldings {
    // 지갑 행이 없으면 findBalance 가 0 을 돌려준다.
    override fun hasBalanceOrPendingRequests(userId: UserId): Boolean =
        wallet.findBalance(userId.value).balance > 0 ||
            deposits.hasPending(userId.value) ||
            withdrawals.hasPending(userId.value)
}
