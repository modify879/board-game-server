package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.model.Wallet

interface WalletRepository {
    fun findByUserId(userId: UserId): Wallet?
    fun save(wallet: Wallet): Wallet

    /**
     * 읽기처럼 보이지만 없으면 저장한다. 지갑은 돈이 움직이는 명령 경로에서만 만들어지므로
     * 이 메서드는 조회 경로에서 부르면 안 된다(규칙 3).
     *
     * 같은 사용자의 첫 두 명령이 동시에 들어오면 둘 다 지갑이 없다고 보고 Wallet.open 을 시도해
     * 진 쪽이 uk_wallets_user 에 걸려 application 계층의 ConcurrentWalletUpdateException(409) 으로 떨어진다.
     * 롤백은 깨끗하고 재시도하면 통과하므로 여기서는 재시도하지 않는다.
     * 재시도는 호출자가 한다 — 탈퇴는 user acl 이 이 예외를 `WalletLockContendedException` 으로 옮기고
     * `WithdrawService` 가 트랜잭션째 한 번 다시 한다.
     */
    fun findOrOpen(userId: UserId): Wallet
}
