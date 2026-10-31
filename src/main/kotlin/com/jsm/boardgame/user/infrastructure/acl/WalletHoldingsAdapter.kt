package com.jsm.boardgame.user.infrastructure.acl

import com.jsm.boardgame.user.application.exception.WalletLockContendedException
import com.jsm.boardgame.user.application.port.WalletHoldings
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.wallet.application.command.usecase.LockWalletForAccountClosureUseCase
import com.jsm.boardgame.wallet.application.exception.ConcurrentWalletUpdateException
import org.springframework.stereotype.Component

@Component
class WalletHoldingsAdapter(
    private val closure: LockWalletForAccountClosureUseCase, // wallet 의 공개 application 계약만 부른다
) : WalletHoldings {
    // wallet 의 예외를 user 의 것으로 옮긴다 — application 이 wallet 예외 타입을 알면 안 되고, 재시도 판단은 WithdrawService 가 한다.
    override fun lockAndHasBalanceOrPendingRequests(userId: UserId): Boolean =
        try {
            !closure.lockAndCheckEmpty(userId.value)
        } catch (e: ConcurrentWalletUpdateException) {
            throw WalletLockContendedException("탈퇴 지갑 락이 첫 지갑 생성과 엇갈림: userId=${userId.value}", e)
        }
}
