package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.LockWalletForAccountClosureUseCase
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * MANDATORY 다 — 스스로 트랜잭션을 열면 확인 직후 락이 풀려 탈퇴와 원자적이지 않다.
 * 지갑이 없어도 findOrOpen 으로 만든다. 없는 행은 잠글 수 없어, 동시에 지갑을 처음 만드는 요청과의 경합을
 * uk_wallets_user 로 직렬화하려는 것이다(진 쪽은 CONCURRENT_WALLET_UPDATE).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class LockWalletForAccountClosureService(
    private val wallets: WalletRepository,
    private val depositRequests: DepositRequestRepository,
    private val withdrawalRequests: WithdrawalRequestRepository,
) : LockWalletForAccountClosureUseCase {

    override fun lockAndCheckEmpty(userId: Long): Boolean {
        val id = UserId(userId)
        val wallet = wallets.findOrOpen(id)
        return wallet.balance.isZero() &&
            !depositRequests.existsPendingByUserId(id) &&
            !withdrawalRequests.existsPendingByUserId(id)
    }
}
