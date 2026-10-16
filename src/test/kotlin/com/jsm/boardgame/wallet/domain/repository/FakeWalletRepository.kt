package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId

/** 서비스 테스트가 공유하는 인메모리 대역. id 가 없는 지갑은 저장할 때 번호를 매긴다. */
class FakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()
    private var sequence = 0L

    override fun findByUserId(userId: UserId): Wallet? = stored[userId.value]

    override fun save(wallet: Wallet): Wallet {
        val saved = if (wallet.id == null) {
            sequence += 1
            Wallet.reconstitute(id = WalletId(sequence), userId = wallet.userId, balance = wallet.balance)
        } else {
            wallet
        }
        stored[wallet.userId.value] = saved
        return saved
    }

    override fun findOrOpen(userId: UserId): Wallet = findByUserId(userId) ?: save(Wallet.open(userId))
}
