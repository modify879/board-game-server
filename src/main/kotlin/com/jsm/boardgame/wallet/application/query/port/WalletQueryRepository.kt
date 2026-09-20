package com.jsm.boardgame.wallet.application.query.port

import com.jsm.boardgame.wallet.application.query.view.LedgerEntryView
import com.jsm.boardgame.wallet.application.query.view.WalletBalanceView
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface WalletQueryRepository {
    fun findBalanceByUserId(userId: Long): WalletBalanceView?
    fun findWalletIdByUserId(userId: Long): Long?
    fun findLedgerByWalletId(walletId: Long, pageable: Pageable): Page<LedgerEntryView>
}
