package com.jsm.boardgame.wallet.presentation.rest.response

import com.jsm.boardgame.wallet.application.query.view.WalletBalanceView

data class WalletBalanceResponse(val balance: Long) {
    companion object {
        fun from(view: WalletBalanceView): WalletBalanceResponse = WalletBalanceResponse(balance = view.balance)
    }
}
