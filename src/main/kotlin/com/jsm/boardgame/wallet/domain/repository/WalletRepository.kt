package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.Wallet

interface WalletRepository {
    fun findByUserId(userId: Long): Wallet?
    fun save(wallet: Wallet): Wallet
}
